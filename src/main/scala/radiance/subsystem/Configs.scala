// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package radiance.subsystem

import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile._
import freechips.rocketchip.subsystem._
import radiance.tile._
import radiance.memory._

class WithRadianceCores(
  n: Int,
  location: HierarchicalLocation,
  crossing: RocketCrossingParams,
  useVxCache: Boolean
) extends Config((site, _, up) => {
  case TilesLocated(`location`) => {
    val prev = up(TilesLocated(`location`), site)
    val idOffset = prev.size
    val vortex = RadianceTileParams(
      core = VortexCoreParams(fpu = None),
      btb = None,
      useVxCache = useVxCache,
      dcache = Some(DCacheParams(
        rowBits = site(SystemBusKey).beatBits,
        nSets = 64,
        nWays = 1,
        nTLBSets = 1,
        nTLBWays = 1,
        nTLBBasePageSectors = 1,
        nTLBSuperpages = 1,
      nMSHRs = 0,
        blockBytes = site(CacheBlockBytes))),
      icache = Some(ICacheParams(
        rowBits = site(SystemBusKey).beatBits,
        nSets = 64,
        nWays = 1,
        nTLBSets = 1,
        nTLBWays = 1,
        nTLBBasePageSectors = 1,
        nTLBSuperpages = 1,
        blockBytes = site(CacheBlockBytes))))
    List.tabulate(n)(i => RadianceTileAttachParams(
      vortex.copy(tileId = i + idOffset),
      crossing
    )) ++ prev
  }
}) {
  def this(n: Int, location: HierarchicalLocation = InSubsystem, useVxCache: Boolean = false) = this(n, location, RocketCrossingParams(
    master = HierarchicalElementMasterPortParams.locationDefault(location),
    slave = HierarchicalElementSlavePortParams.locationDefault(location),
    mmioBaseAddressPrefixWhere = location match {
      case InSubsystem => CBUS
      case InCluster(clusterId) => CCBUS(clusterId)
    }
  ), useVxCache)
}

class WithFuzzerCores(
  n: Int,
  useVxCache: Boolean
) extends Config((site, _, up) => {
  case TilesLocated(InSubsystem) => {
    val prev = up(TilesLocated(InSubsystem), site)
    val idOffset = prev.size
    val fuzzer = FuzzerTileParams(
      core = VortexCoreParams(fpu = None),
      useVxCache = useVxCache)
    List.tabulate(n)(i => FuzzerTileAttachParams(
      fuzzer.copy(tileId = i + idOffset),
      RocketCrossingParams()
    )) ++ prev
  }
})

// `nSrcIds`: number of source IDs for dmem requests on each SIMT lane
class WithSimtConfig(nWarps: Int = 4, nCoreLanes: Int = 4, nMemLanes: Int = 4, nSrcIds: Int = 8)
extends Config((site, _, up) => {
  case SIMTCoreKey => {
    Some(up(SIMTCoreKey, site).getOrElse(SIMTCoreParams()).copy(
      nWarps = nWarps,
      nCoreLanes = nCoreLanes,
      nMemLanes = nMemLanes,
      nSrcIds = nSrcIds
      ))
  }
})

class WithMemtraceCore(tracefilename: String, traceHasSource: Boolean = false)
extends Config((site, _, _) => {
  case MemtraceCoreKey => {
    require(
      site(SIMTCoreKey).isDefined,
      "Memtrace core requires a SIMT configuration. Use WithNLanes to enable SIMT."
    )
    Some(MemtraceCoreParams(tracefilename, traceHasSource))
  }
})

class WithPriorityCoalXbar extends Config((site, _, up) => {
  case CoalXbarKey => {
    Some(up(CoalXbarKey, site).getOrElse(CoalXbarParam))
  }
})

class WithVortexL1Banks(nBanks: Int = 4) extends Config ((site, _, up) => {
  case VortexL1Key => {
    Some(defaultVortexL1Config.copy(numBanks = nBanks))
  }
})

// When `enable` is false, we still elaborate Coalescer, but it acts as a
// pass-through logic that always outputs un-coalesced requests.  This is
// useful for when we want to keep the generated wire and net names the same
// to e.g. compare waveforms.
class WithCoalescer(nNewSrcIds: Int = 8, enable : Boolean = true) extends Config((site, _, up) => {
  case CoalescerKey => {
    val (nLanes, numOldSrcIds) = up(SIMTCoreKey, site) match {
      case Some(param) => (param.nMemLanes, param.nSrcIds)
      case None => (1,1)
    }

    val sbusWidthInBytes = site(SystemBusKey).beatBytes
    // FIXME: coalescer fails to instantiate with 4-byte bus
    require(sbusWidthInBytes > 2,
      "FIXME: coalescer currently doesn't instantiate with 4-byte sbus")

    // If instantiating L1 cache, the maximum coalescing size should match the
    // cache line size
    val maxCoalSizeInBytes = up(VortexL1Key, site) match {
      case Some(param) =>
        (param.wordSize) 
      case None => sbusWidthInBytes
    }
      
    // Note: this config chooses a single-sized coalescing logic by default.
    Some(DefaultCoalescerConfig.copy(
      enable       = enable,
      numLanes     = nLanes,
      numOldSrcIds = numOldSrcIds,
      numNewSrcIds = nNewSrcIds,
      addressWidth = 32, // FIXME hardcoded as 32-bit system
      dataBusWidth = log2Ceil(maxCoalSizeInBytes),
      coalLogSizes = Seq(log2Ceil(maxCoalSizeInBytes))
      )
    )
  }
})

class WithNCustomSmallRocketCores(
                             n: Int,
                             overrideIdOffset: Option[Int] = None,
                             crossing: RocketCrossingParams = RocketCrossingParams()
                           ) extends Config((site, here, up) => {
  case TilesLocated(InSubsystem) => {
    val prev = up(TilesLocated(InSubsystem), site)
    val idOffset = overrideIdOffset.getOrElse(prev.size)
    val med = RocketTileParams(
      core = RocketCoreParams(fpu = None),
      btb = None,
      dcache = Some(DCacheParams(
        rowBits = site(SystemBusKey).beatBits,
        nSets = 2,
        nWays = 1,
        nTLBSets = 1,
        nTLBWays = 2,
        nTLBBasePageSectors = 1,
        nTLBSuperpages = 1,
        nMSHRs = 0,
        blockBytes = site(CacheBlockBytes))),
      icache = Some(ICacheParams(
        rowBits = site(SystemBusKey).beatBits,
        nSets = 2,
        nWays = 1,
        nTLBSets = 1,
        nTLBWays = 2,
        nTLBBasePageSectors = 1,
        nTLBSuperpages = 1,
        blockBytes = site(CacheBlockBytes))))
    List.tabulate(n)(i => RocketTileAttachParams(
      med.copy(tileId = i + idOffset),
      crossing
    )) ++ prev
  }
})

class WithExtGPUMem(address: BigInt = BigInt("0x100000000", 16),
                    size: BigInt = 0x80000000) extends Config((site, here, up) => {
  case GPUMemory() => Some(GPUMemParams(address, size))
  case ExtMem => up(ExtMem, site).map(x => {
    val gap = address - x.master.base - x.master.size
    x.copy(master = x.master.copy(size = x.master.size + gap + size))
  })
})
case class GPUMemParams(address: BigInt = BigInt("0x100000000", 16), size: BigInt = 0x80000000)
case class GPUMemory() extends Field[Option[GPUMemParams]](None)
