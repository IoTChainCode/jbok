package jbok.evm

import jbok.core.models._
import scodec.bits.ByteVector

/** Execution environment constants of an EVM program.
  * See section 9.3 in Yellow Paper for more detail.
  *
  * @param ownerAddr   I_a: address of the account that owns the code
  * @param callerAddr  I_s: address of the account which caused the code to be executing
  * @param originAddr  I_o: sender address of the transaction that originated this execution
  * @param gasPrice    I_p
  * @param inputData   I_d
  * @param value       I_v
  * @param program     I_b
  * @param blockHeader I_H
  * @param callDepth   I_e
  */
case class ExecEnv(
    ownerAddr: Address,
    callerAddr: Address,
    originAddr: Address,
    gasPrice: UInt256,
    inputData: ByteVector,
    value: UInt256,
    program: Program,
    blockHeader: BlockHeader,
    callDepth: Int,
    noSelfCall: Boolean = false
)
