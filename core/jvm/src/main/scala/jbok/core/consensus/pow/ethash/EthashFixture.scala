package jbok.core.consensus.pow.ethash

import cats.effect.IO
import jbok.core.History
import jbok.core.config.Configs.{BlockChainConfig, DaoForkConfig, MiningConfig}
import jbok.core.consensus.ConsensusFixture
import jbok.core.mining.TxGen
import jbok.core.pool.BlockPool
import jbok.common.execution._
import jbok.persistent.KeyValueDB

trait EthashFixture extends ConsensusFixture {
  val db               = KeyValueDB.inMemory[IO].unsafeRunSync()
  val history          = History[IO](db).unsafeRunSync()
  val blockChainConfig = BlockChainConfig()
  val miningConfig     = MiningConfig()
  val daoForkConfig    = DaoForkConfig()
  val ethashMiner      = EthashMinerPlatform[IO](miningConfig).unsafeRunSync()
  val blockPool        = BlockPool[IO](history).unsafeRunSync()

  val txGen = new TxGen(3)
  //  val blockChainGen = new BlockChainGen(txGen)
  val miners        = txGen.addresses.take(2).toList
  val genesisConfig = txGen.genesisConfig
  history.loadGenesisConfig(genesisConfig).unsafeRunSync()

  val consensus = new EthashConsensus[IO](
    blockChainConfig,
    miningConfig,
    history,
    blockPool,
    ethashMiner,
    new EthashOmmersValidator[IO](history, blockChainConfig, daoForkConfig),
    new EthashHeaderValidator[IO](blockChainConfig, daoForkConfig)
  )
}
