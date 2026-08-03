package cluster

import scala.collection.{mutable => mut}

import assembler.RISCVAssembler

import chisel3.util._

class TestMem {
  // Use CoreTop(UDACoreTop parameters and interfaces using core api!)

  // n byte align
  val alignByte: Int = dataWidth / 8
  val mem            = mut.Map[BigInt, BigInt]()

  def alignAddr(addr: BigInt) =
    ((addr >> log2Ceil(alignByte)) << 2, addr & (alignByte - 1))
//  def alignAddr(addr: BigInt) = ((addr >> 2) << 2, addr & 3)

  def load(addr: BigInt) = {
    val (aligned, offset) = alignAddr(addr)
    val ret               = mem.get(aligned).getOrElse(BigInt(0))
    ret >> (offset.toInt * 8)
  }

  def store(addr: BigInt, v: BigInt) = {
    val (aligned, offset) = alignAddr(addr)
    mem(aligned) = v << (offset.toInt * 8)
  }

  def hexToBigInt(hex: String): BigInt = {
    hex
      .toLowerCase()
      .toList
      .map {
        "0123456789abcdef".indexOf(_)
      }
      .map(BigInt(_))
      .reduceLeft(_ * 16 + _)
  }

  def setup(start: BigInt, bin: Seq[BigInt]) = {
    var alignAddr: BigInt = null
    var alignData: BigInt = null
    Seq
      .tabulate(bin.size)(start + _ * 4)
      .zip(bin)
      .foreach { case (a, v) =>
        println(f"[Mem] Setup Request addr($a%X) data($v%X)")
        val shiftAmount: Int = 32 * (a % alignByte / 4).toInt
        if (a % alignByte == 0) {
          alignAddr = a
          alignData = v
        } else {
          alignData = alignData | (v << shiftAmount)
        }

        if (a % alignByte / 4 == (alignByte / 4 - 1)) {
          alignData = alignData | (v << shiftAmount)
          mem(alignAddr) = alignData
          //          store(a, v)
          store(alignAddr, alignData)
          //        store(a, v)
        }
      }
    print("mem: ")
    println(
      mem.toList
        .sortBy(_._1)
        .map(m =>
          m._1.toString(16).concat(": ").concat(m._2.toString(16)).concat("\n")
        )
    )
  }

  def setupText(start: BigInt, text: String) = {
    val bin =
      RISCVAssembler.fromString(text).trim.split("\n").map(hexToBigInt(_))
    setup(start, bin.toSeq)
  }

  def setupData(start: BigInt, data: String) = {
    val bin = data.trim.split("\n").map(hexToBigInt(_))
    setup(start, bin.toSeq)
  }

  def dump(addrs: Seq[BigInt]) = addrs.map { x => load(x) }
}
