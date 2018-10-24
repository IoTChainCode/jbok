package jbok.core.mining

import cats.effect.IO
import jbok.common.execution._
import jbok.core.config.Configs.{BlockChainConfig, PeerManagerConfig, SyncConfig}
import jbok.core.consensus.ConsensusFixture
import jbok.core.ledger.BlockExecutor
import jbok.core.peer.PeerManager
import jbok.core.pool._
import jbok.core.sync.{Broadcaster, FullSync, Synchronizer}

class BlockMinerFixture(consensusFixture: ConsensusFixture, port: Int = 9999) {
  val txGen            = consensusFixture.txGen
  val consensus        = consensusFixture.consensus
  val blockChainConfig = BlockChainConfig()
  val history          = consensus.history
  val blockPoolConfig = BlockPoolConfig()
  val blockPool       = BlockPool[IO](history, blockPoolConfig).unsafeRunSync()
  val executor        = BlockExecutor[IO](blockChainConfig, history, blockPool, consensus)

  val syncConfig        = SyncConfig()
  val peerManagerConfig = PeerManagerConfig(port)
  val peerManager =
    PeerManager[IO](peerManagerConfig, syncConfig, history).unsafeRunSync()
  val txPool       = TxPool[IO](peerManager, TxPoolConfig()).unsafeRunSync()
  val ommerPool    = OmmerPool[IO](history).unsafeRunSync()
  val broadcaster  = Broadcaster[IO](peerManager)
  val synchronizer = Synchronizer[IO](peerManager, executor, txPool, ommerPool, broadcaster).unsafeRunSync()

  val fullSync = FullSync[IO](syncConfig, peerManager, executor, txPool)
  val miner    = BlockMiner[IO](synchronizer).unsafeRunSync()
}
