// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package radiance.core

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile

// Implements the four-element dot product (FEDP) unit in Volta Tensor Cores.
// `half`: if True, generate fp16 MACs; if False fp32.
class TensorDotProductUnit(val half: Boolean) extends Module with tile.HasFPUParameters {
  val tIn = if (half) tile.FType.H else tile.FType.S
  // output datatype fixed to single-precision
  val tOut = tile.FType.S

  val inFLen = tIn.ieeeWidth
  val outFLen = tOut.ieeeWidth
  val fLen = outFLen // needed for HasFPUParameters
  val minFLen = 16 // fp16
  def xLen = 32
  val dotProductDim = 4

  val io = IO(new Bundle {
    val in = Flipped(Valid(new Bundle {
      val a = Vec(dotProductDim, Bits((inFLen).W))
      val b = Vec(dotProductDim, Bits((inFLen).W))
      val c = Bits((outFLen).W) // note C has the out length for accumulation
    }))
    // 'stall' is effectively out.ready, combinationally coupled to in.ready
    val stall = Input(Bool())
    val out = Valid(new Bundle {
      val data = Bits((outFLen).W)
    })
  })

  // [IEEE] -> recode() -> unbox() -> [Hardfloat] -> box() -> ieee() -> [IEEE]
  // make sure recoding/uncoding happens only at the edge, not at every
  // pipeline stage inside the dpu
  val tag = if (half) H else S
  val in1 = io.in.bits.a.map(x => unbox(recode(x, tag), tag, Some(tIn)))
  val in2 = io.in.bits.b.map(x => unbox(recode(x, tag), tag, Some(tIn)))
  val in3 = unbox(recode(io.in.bits.c, S), S, Some(tOut))

  val dpu = Module(new DotProductPipe(dotProductDim, tIn, tOut))
  dpu.io.in.valid := io.in.valid
  dpu.io.in.bits.a := in1
  dpu.io.in.bits.b := in2
  dpu.io.in.bits.c := in3
  dpu.io.stall := io.stall

  io.out.valid := dpu.io.out.valid
  io.out.bits.data := ieee(box(dpu.io.out.bits.data, S))
}

// Copied from chisel3.util.Pipe.
class StallingPipe[T <: Data](val gen: T, val latency: Int = 1) extends Module {
  /** A non-ambiguous name of this `StallingPipe` for use in generated Verilog
   *  names. Includes the latency cycle count in the name as well as the
   *  parameterized generator's `typeName`, e.g. `Pipe4_UInt4`
    */
  // override def desiredName = s"${simpleClassName(this.getClass)}${latency}_${gen.typeName}"

  class StallingPipeIO extends Bundle {
    val stall = Input(Bool())
    val enq = Input(Valid(gen))
    val deq = Output(Valid(gen))
  }

  require(latency == 1, "StallingPipe only supports latency equals one!")

  val io = IO(new StallingPipeIO)

  val v = RegEnable(io.enq.valid, false.B, !io.stall)
  val b = RegEnable(io.enq.bits, !io.stall && io.enq.valid)
  io.deq.valid := v
  io.deq.bits := b
}

object StallingPipe {
  import chisel3.experimental.prefix

  def apply[T <: Data](stall: Bool, enqValid: Bool, enqBits: T, latency: Int): Valid[T] = {
    val p = Module(new StallingPipe(chiselTypeOf(enqBits), latency))
    p.io.stall := stall
    p.io.enq.valid := enqValid
    p.io.enq.bits := enqBits
    p.io.deq
  }

  def apply[T <: Data](stall: Bool, enqValid: Bool, enqBits: T): Valid[T] = {
    apply(stall, enqValid, enqBits, 1)
  }

  def apply[T <: Data](stall: Bool, enq: Valid[T], latency: Int = 1): Valid[T] = {
    apply(stall, enq.valid, enq.bits, latency)
  }
}

// Computes d = a(0)*b(0) + ... + a(`dim`-1)*b(`dim`-1) + c.
// Fully pipelined with a fixed latency determined by `dim`.
class DotProductPipe(dim: Int, inputType: tile.FType, outputType: tile.FType) extends Module {
  require(dim == 4, "DPU currently only supports dimension 4")
  val expWidth = inputType.exp
  val sigWidth = inputType.sig
  val outExpWidth = outputType.exp
  val outSigWidth = outputType.sig

  val recInFLen = expWidth + sigWidth + 1
  val recOutFLen = outExpWidth + outSigWidth + 1
  val io = IO(new Bundle {
    val in = Flipped(Valid(new Bundle {
      val a = Vec(4, Bits((recInFLen).W))
      val b = Vec(4, Bits((recInFLen).W))
      val c = Bits((recOutFLen).W)
      // val roundingMode   = UInt(3.W)
      // val detectTininess = UInt(1.W)
    }))
    val stall = Input(Bool())
    val out = Valid(new Bundle {
      val data = Bits((recOutFLen).W)
    })
  })

  val rawZero = hardfloat.rawFloatFromRecFN(expWidth, sigWidth, 0.U(recInFLen.W))
  val mul = Seq.fill(dim)(Module(new hardfloat.MulFullRawFN(expWidth, sigWidth)))
  val mulOuts = mul.zipWithIndex.map { case (m, i) =>
    val rawInA = hardfloat.rawFloatFromRecFN(expWidth, sigWidth, io.in.bits.a(i))
    val rawInB = hardfloat.rawFloatFromRecFN(expWidth, sigWidth, io.in.bits.b(i))
    // assert(rawInA.isNaN === false.B)
    // assert(rawInA.isInf === false.B)
    // assert(rawInB.isNaN === false.B)
    // assert(rawInB.isInf === false.B)

    // tie down to zero when invalid
    val rawInAOr0 = Mux(io.in.valid, rawInA, rawZero)
    val rawInBOr0 = Mux(io.in.valid, rawInB, rawZero)
    m.io.a := rawInAOr0
    m.io.b := rawInBOr0
    // assert(m.io.invalidExc === false.B)

    // round fp16*fp16 raw result back to fp32 recoded format
    val mulExpWidth = m.io.rawOut.expWidth
    val mulSigWidth = m.io.rawOut.sigWidth
    val roundRawFNToRecFN =
        Module(new hardfloat.RoundAnyRawFNToRecFN(
               mulExpWidth, mulSigWidth, outExpWidth, outSigWidth, 0))
    roundRawFNToRecFN.io.invalidExc   := m.io.invalidExc
    roundRawFNToRecFN.io.infiniteExc  := false.B
    roundRawFNToRecFN.io.in           := m.io.rawOut
    roundRawFNToRecFN.io.roundingMode := hardfloat.consts.round_near_even
    roundRawFNToRecFN.io.detectTininess := hardfloat.consts.tininess_afterRounding
    // assert(roundRawFNToRecFN.io.exceptionFlags === 0.U)
    roundRawFNToRecFN.io.out
  }

  val mulStageOut = StallingPipe(io.stall, io.in.valid, VecInit(mulOuts))
  val mulStageC   = StallingPipe(io.stall, io.in.valid, io.in.bits.c)

  // mul stage end -------------------------------------------------------------

  val add1 = Seq.fill(dim / 2)(Module(new hardfloat.AddRecFN(outExpWidth, outSigWidth)))
  val add1Outs = add1.zipWithIndex.map { case (a, i) =>
    a.io.subOp := 0.U // FIXME dont know what this is
    a.io.a := mulStageOut.bits(2 * i + 0)
    a.io.b := mulStageOut.bits(2 * i + 1)
    a.io.roundingMode := hardfloat.consts.round_near_even
    a.io.detectTininess := hardfloat.consts.tininess_afterRounding
    // assert(a.io.exceptionFlags === 0.U)
    a.io.out
  }

  val add1StageOut = StallingPipe(io.stall, mulStageOut.valid, VecInit(add1Outs))
  val add1StageC   = StallingPipe(io.stall, mulStageOut.valid, mulStageC.bits)

  // add1 stage end ------------------------------------------------------------

  val add2 = Module(new hardfloat.AddRecFN(outExpWidth, outSigWidth))
  add2.io.subOp := 0.U // FIXME
  add2.io.a := add1StageOut.bits(0)
  add2.io.b := add1StageOut.bits(1)
  add2.io.roundingMode := hardfloat.consts.round_near_even
  add2.io.detectTininess := hardfloat.consts.tininess_afterRounding
  // assert(add2.io.exceptionFlags === 0.U)

  val add2StageOut = StallingPipe(io.stall, add1StageOut.valid, add2.io.out)
  val add2StageC   = StallingPipe(io.stall, add1StageOut.valid, add1StageC.bits)

  // add2 stage end ------------------------------------------------------------

  val acc = Module(new hardfloat.AddRecFN(outExpWidth, outSigWidth))
  acc.io.subOp := 0.U // FIXME
  acc.io.a := add2StageOut.bits
  // acc.io.b := add2StageCRec
  acc.io.b := add2StageC.bits
  acc.io.roundingMode := hardfloat.consts.round_near_even
  acc.io.detectTininess := hardfloat.consts.tininess_afterRounding
  // assert(acc.io.exceptionFlags === 0.U)

  val accStageOut = StallingPipe(io.stall, add2StageOut.valid, acc.io.out)

  // acc stage end -------------------------------------------------------------

  io.out.valid := accStageOut.valid
  io.out.bits.data := accStageOut.bits
}

class MulAddRecFNPipe(latency: Int, expWidth: Int, sigWidth: Int) extends Module {
  require(latency <= 2)

  val io = IO(new Bundle {
    val validin = Input(Bool())
    val op = Input(Bits(2.W))
    val a = Input(Bits((expWidth + sigWidth + 1).W))
    val b = Input(Bits((expWidth + sigWidth + 1).W))
    val c = Input(Bits((expWidth + sigWidth + 1).W))
    val roundingMode   = Input(UInt(3.W))
    val detectTininess = Input(UInt(1.W))
    val out = Output(Bits((expWidth + sigWidth + 1).W))
    val exceptionFlags = Output(Bits(5.W))
    val validout = Output(Bool())
  })

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------

  val mulAddRecFNToRaw_preMul = Module(new hardfloat.MulAddRecFNToRaw_preMul(expWidth, sigWidth))
  val mulAddRecFNToRaw_postMul = Module(new hardfloat.MulAddRecFNToRaw_postMul(expWidth, sigWidth))

  mulAddRecFNToRaw_preMul.io.op := io.op
  mulAddRecFNToRaw_preMul.io.a  := io.a
  mulAddRecFNToRaw_preMul.io.b  := io.b
  mulAddRecFNToRaw_preMul.io.c  := io.c

  val mulAddResult =
      (mulAddRecFNToRaw_preMul.io.mulAddA *
           mulAddRecFNToRaw_preMul.io.mulAddB) +&
          mulAddRecFNToRaw_preMul.io.mulAddC

  val valid_stage0 = Wire(Bool())
  val roundingMode_stage0 = Wire(UInt(3.W))
  val detectTininess_stage0 = Wire(UInt(1.W))

  val postmul_regs = if(latency>0) 1 else 0
  mulAddRecFNToRaw_postMul.io.fromPreMul   := Pipe(io.validin, mulAddRecFNToRaw_preMul.io.toPostMul, postmul_regs).bits
  mulAddRecFNToRaw_postMul.io.mulAddResult := Pipe(io.validin, mulAddResult, postmul_regs).bits
  mulAddRecFNToRaw_postMul.io.roundingMode := Pipe(io.validin, io.roundingMode, postmul_regs).bits
  roundingMode_stage0                      := Pipe(io.validin, io.roundingMode, postmul_regs).bits
  detectTininess_stage0                    := Pipe(io.validin, io.detectTininess, postmul_regs).bits
  valid_stage0                             := Pipe(io.validin, false.B, postmul_regs).valid

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------

  val roundRawFNToRecFN = Module(new hardfloat.RoundRawFNToRecFN(expWidth, sigWidth, 0))

  val round_regs = if(latency==2) 1 else 0
  roundRawFNToRecFN.io.invalidExc         := Pipe(valid_stage0, mulAddRecFNToRaw_postMul.io.invalidExc, round_regs).bits
  roundRawFNToRecFN.io.in                 := Pipe(valid_stage0, mulAddRecFNToRaw_postMul.io.rawOut, round_regs).bits
  roundRawFNToRecFN.io.roundingMode       := Pipe(valid_stage0, roundingMode_stage0, round_regs).bits
  roundRawFNToRecFN.io.detectTininess     := Pipe(valid_stage0, detectTininess_stage0, round_regs).bits
  io.validout                             := Pipe(valid_stage0, false.B, round_regs).valid

  roundRawFNToRecFN.io.infiniteExc := false.B

  io.out            := roundRawFNToRecFN.io.out
  io.exceptionFlags := roundRawFNToRecFN.io.exceptionFlags
}
