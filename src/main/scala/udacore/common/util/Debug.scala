package udacore.common.util

import chisel3._
import chisel3.util._

/** Simple cycle counter used in debug printouts */
object GTimer {
  def apply(): UInt = {
    val cnt = RegInit(0.U(64.W))
    cnt := cnt + 1.U
    cnt
  }
}

/** Debug utility functions for UDACore core.
  *
  * Temporarily simplified to remove Parameters dependency until debug parameter
  * system is implemented in the new architecture.
  */
object Debug {

  /** Simple debug print with condition - no parameters needed */
  def apply(cond: Bool, msg: Printable): Unit = {
    when(cond) {
      printf(msg)
    }
  }

  /** Always print debug message */
  def apply(msg: Printable): Unit = {
    printf(msg)
  }

  // All other debug functions temporarily disabled until parameter system is ready
  def dcacheLog(addr: UInt, rtype: String, msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }

  def dcacheLogReq(addr: UInt, rtype: String, msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }

  def dcacheLogResp(addr: UInt, rtype: String, msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }

  def icacheLog(addr: UInt, msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }

  def icacheLogReq(addr: UInt, msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }

  def icacheLogResp(addr: UInt, msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }

  def l2cacheLog(addr: UInt, msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }

  def l2cacheLogReq(addr: UInt, msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }

  def l2cacheLogResp(addr: UInt, msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }

  def fetchLog(msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }

  def decodeLog(msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }

  def renameLog(msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }

  def dispatchLog(msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }

  def issueLog(msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }

  def executeLog(msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }

  def commitLog(msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }

  def robLog(msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }

  def csrLog(msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }

  def tlbLog(msg: Printable): Unit = {
    // TODO: Re-implement when debug parameters are available
  }
}
