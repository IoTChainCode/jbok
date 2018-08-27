package jbok.core.api.impl

import java.time.Duration
import java.util.Date

import cats.data.{EitherT, OptionT}
import cats.effect.IO
import fs2.async.Ref
import jbok.codec.rlp.RlpCodec
import jbok.codec.rlp.codecs._
import jbok.core.api._
import cats.implicits._
import jbok.core.configs.{BlockChainConfig, MiningConfig}
import jbok.core.consensus.pow.Ethash
import jbok.core.keystore.KeyStore
import jbok.core.ledger.{Ledger, OmmersPool}
import jbok.core.mining.BlockGenerator
import jbok.core.models._
import jbok.core.{BlockChain, TxPool}
import jbok.crypto.signature.CryptoSignature
import scodec.bits.ByteVector

class PublicApiImpl(
    blockChain: BlockChain[IO],
    blockChainConfig: BlockChainConfig,
    ommersPool: OmmersPool[IO],
    txPool: TxPool[IO],
    ledger: Ledger[IO],
    blockGenerator: BlockGenerator[IO],
    keyStore: KeyStore[IO],
    filterManager: FilterManager[IO],
    miningConfig: MiningConfig,
    version: Int,
    hashRate: Ref[IO, Map[ByteVector, (BigInt, Date)]],
    lastActive: Ref[IO, Option[Date]]
) extends PublicAPI {
  override def protocolVersion: Response[String] =
    ok(f"0x${version}%x")

  override def bestBlockNumber: Response[BigInt] =
    ok(blockChain.getBestBlockNumber)

  override def getBlockTransactionCountByHash(blockHash: ByteVector): Response[Option[Int]] = {
    val count = blockChain.getBlockBodyByHash(blockHash).map(_.map(_.transactionList.length))
    ok(count)
  }

  override def getBlockByHash(blockHash: ByteVector): Response[Option[Block]] =
    ok(blockChain.getBlockByHash(blockHash))

  override def getBlockByNumber(blockNumber: BigInt): Response[Option[Block]] =
    ok(blockChain.getBlockByNumber(blockNumber))

  override def getTransactionByHash(txHash: ByteVector): Response[Option[SignedTransaction]] = {
    val pending = OptionT(txPool.getPendingTransactions.map(_.map(_.stx).find(_.hash == txHash)))
    val inBlock = for {
      loc   <- OptionT(blockChain.getTransactionLocation(txHash))
      block <- OptionT(blockChain.getBlockByHash(loc.blockHash))
      stx   <- OptionT.fromOption[IO](block.body.transactionList.lift(loc.txIndex))
    } yield stx

    ok(pending.orElseF(inBlock.value).value)
  }

  override def getTransactionReceipt(txHash: ByteVector): Response[Option[Receipt]] = {
    val r = for {
      loc <- OptionT(blockChain.getTransactionLocation(txHash))
      block <- OptionT(blockChain.getBlockByHash(loc.blockHash))
      stx <- OptionT.fromOption[IO](block.body.transactionList.lift(loc.txIndex))
      receipts <- OptionT(blockChain.getReceiptsByHash(loc.blockHash))
      receipt <- OptionT.fromOption[IO](receipts.lift(loc.txIndex))
    } yield receipt

    ok(r)
  }

  override def getTransactionByBlockHashAndIndexRequest(blockHash: ByteVector,
                                                        txIndex: Int): Response[Option[SignedTransaction]] = {
    val x = for {
      block <- OptionT(blockChain.getBlockByHash(blockHash))
      stx <- OptionT.fromOption[IO](block.body.transactionList.lift(txIndex))
    } yield stx

    ok(x)
  }

  override def getUncleByBlockHashAndIndex(blockHash: ByteVector, uncleIndex: Int): Response[Option[BlockHeader]] = {
    val x = for {
      block <- OptionT(blockChain.getBlockByHash(blockHash))
      uncle <- OptionT.fromOption[IO](block.body.uncleNodesList.lift(uncleIndex))
    } yield uncle

    ok(x)
  }

  override def getUncleByBlockNumberAndIndex(blockParam: BlockParam, uncleIndex: Int): Response[Option[BlockHeader]] = {
    val x = for {
      block <- OptionT.liftF(resolveBlock(blockParam))
      uncle <- OptionT.fromOption[IO](block.body.uncleNodesList.lift(uncleIndex))
    } yield uncle

    ok(x)
  }

  override def submitHashRate(hr: BigInt, id: ByteVector): Response[Boolean] = {
    val x = for {
      _ <- reportActive
      now = new Date
      _ <- hashRate.modify(m => removeObsoleteHashrates(now, m + (id -> (hr, now))))
    } yield true

    ok(x)
  }

  override def getGasPrice: Response[BigInt] = ok {
    val blockDifference = BigInt(30)
    for {
      bestBlock <- blockChain.getBestBlockNumber
      gasPrices <- ((bestBlock - blockDifference) to bestBlock).toList
        .traverse(blockChain.getBlockByNumber)
        .map(_.flatten.flatMap(_.body.transactionList).map(_.gasPrice))
      gasPrice = if (gasPrices.nonEmpty) {
        gasPrices.sum / gasPrices.length
      } else {
        BigInt(0)
      }
    } yield gasPrice
  }

  override def getMining: Response[Boolean] =
    ok {
      lastActive
        .modify(e =>
          e.filter(time =>
            Duration.between(time.toInstant, (new Date).toInstant).toMillis < miningConfig.activeTimeout.toMillis))
        .map(_.now.isDefined)
    }

  override def getHashRate: Response[BigInt] = ok {
    hashRate.modify(m => removeObsoleteHashrates(new Date, m)).map(_.now.map(_._2._1).sum)
  }

  override def getWork: Response[GetWorkResponse] = ok {
    for {
      _         <- reportActive
      bestBlock <- blockChain.getBestBlock
      ommers    <- ommersPool.getOmmers(bestBlock.header.number + 1)
      txs       <- txPool.getPendingTransactions
      resp <- blockGenerator
        .generateBlockForMining(bestBlock, txs.map(_.stx), ommers, miningConfig.coinbase)
        .value
        .flatMap {
          case Right(pb) =>
            GetWorkResponse(
              powHeaderHash = pb.block.header.hashWithoutNonce,
              dagSeed = Ethash.seed(Ethash.epoch(pb.block.header.number.toLong)),
              target = ByteVector((BigInt(2).pow(256) / pb.block.header.difficulty).toByteArray)
            ).pure[IO]
          case Left(err) =>
            IO.raiseError[GetWorkResponse](new RuntimeException("unable to prepare block"))
        }
    } yield resp
  }

  override def getCoinbase: Response[Address] = {
    ok(miningConfig.coinbase.pure[IO])
  }

  override def submitWork(nonce: ByteVector, powHeaderHash: ByteVector, mixHash: ByteVector): Response[Boolean] = {
    val succeed = for {
      _  <- reportActive
      bn <- blockChain.getBestBlockNumber
      succeed <- blockGenerator.getPrepared(powHeaderHash).map {
        case Some(pendingBlock) if bn <= pendingBlock.block.header.number =>
          true
        case _ =>
          false
      }
    } yield succeed
    ok(succeed)
  }

  override def syncing: Response[Option[SyncingStatus]] = {
    val status = for {
      currentBlock  <- blockChain.getBestBlockNumber
      highestBlock  <- blockChain.getEstimatedHighestBlock
      startingBlock <- blockChain.getSyncStartingBlock
    } yield {
      if (currentBlock < highestBlock) {
        Some(
          SyncingStatus(
            startingBlock,
            currentBlock,
            highestBlock
          ))
      } else {
        None
      }
    }

    ok(status)
  }

  override def sendRawTransaction(data: ByteVector): Response[ByteVector] = {
    val stx = RlpCodec.decode[SignedTransaction](data.bits).require.value
    val txHash = for {
      _ <- txPool.addOrUpdateTransaction(stx)
    } yield stx.hash
    ok(txHash)
  }

  override def call(callTx: CallTx, blockParam: BlockParam): Response[ByteVector] = {
    val returnData = for {
      (stx, block) <- doCall(callTx, blockParam)
      txResult     <- ledger.simulateTransaction(stx, block.header)
    } yield txResult.vmReturnData

    ok(returnData)
  }

  override def estimateGas(callTx: CallTx, blockParam: BlockParam): Response[BigInt] = {
    val gas = for {
      (stx, block) <- doCall(callTx, blockParam)
      gas          <- ledger.binarySearchGasEstimation(stx, block.header)
    } yield gas

    ok(gas)
  }

  override def getCode(address: Address, blockParam: BlockParam): Response[ByteVector] = {
    val code = for {
      block <- resolveBlock(blockParam)
      world <- blockChain.getWorldStateProxy(block.header.number,
                                             blockChainConfig.accountStartNonce,
                                             Some(block.header.stateRoot))
      code <- world.getCode(address)
    } yield code

    ok(code)
  }

  override def getUncleCountByBlockNumber(blockParam: BlockParam): Response[Int] = ok {
    for {
      block <- resolveBlock(blockParam)
    } yield block.body.uncleNodesList.length
  }

  override def getUncleCountByBlockHash(blockHash: ByteVector): Response[Int] = {
    val count = for {
      body <- blockChain.getBlockBodyByHash(blockHash)
    } yield body.map(_.uncleNodesList.length).getOrElse(-1)
    ok(count)
  }

  override def getBlockTransactionCountByNumber(blockParam: BlockParam): Response[Int] = ok {
    resolveBlock(blockParam).map(_.body.transactionList.length)
  }

  override def getTransactionByBlockNumberAndIndexRequest(
      blockParam: BlockParam,
      txIndex: Int
  ): Response[Option[SignedTransaction]] = ok {
    for {
      block <- resolveBlock(blockParam)
      tx = block.body.transactionList.lift(txIndex)
    } yield tx
  }

  override def getBalance(address: Address, blockParam: BlockParam): Response[BigInt] = ok {
    for {
      account <- resolveAccount(address, blockParam)
    } yield account.balance.toBigInt
  }

  override def getStorageAt(address: Address, position: BigInt, blockParam: BlockParam): Response[ByteVector] = ok {
    for {
      account <- resolveAccount(address, blockParam)
      storage <- blockChain.getAccountStorageAt(account.storageRoot, position)
    } yield storage
  }

  override def getTransactionCount(address: Address, blockParam: BlockParam): Response[BigInt] = ok {
    for {
      account <- resolveAccount(address, blockParam)
    } yield account.nonce.toBigInt
  }

  override def newFilter(
      fromBlock: Option[BlockParam],
      toBlock: Option[BlockParam],
      address: Option[Address],
      topics: List[List[ByteVector]]
  ): Response[BigInt] = {
    val id = filterManager.newLogFilter(fromBlock, toBlock, address, topics)
    ok(id)
  }

  override def newBlockFilter: Response[BigInt] = {
    val id = filterManager.newBlockFilter
    ok(id)
  }

  override def newPendingTransactionFilter: Response[BigInt] = {
    val id = filterManager.newPendingTxFilter
    ok(id)
  }

  override def uninstallFilter(filterId: BigInt): Response[Boolean] = {
    ok(filterManager.uninstallFilter(filterId).map(_ => true))
  }

  override def getFilterChanges(filterId: BigInt): Response[FilterChanges] = {
    ok(filterManager.getFilterChanges(filterId))
  }

  override def getFilterLogs(filterId: BigInt): Response[FilterLogs] = {
    ok(filterManager.getFilterLogs(filterId))
  }

  override def getLogs(
      fromBlock: Option[BlockParam],
      toBlock: Option[BlockParam],
      address: Option[Address],
      topics: List[List[ByteVector]]
  ): Response[LogFilterLogs] = {
    val logs = filterManager
      .getLogs(LogFilter(0, fromBlock, toBlock, address, topics))
      .map(LogFilterLogs)

    ok(logs)
  }

  override def getAccountTransactions(address: Address,
                                      fromBlock: BigInt,
                                      toBlock: BigInt): Response[List[Transaction]] = {
    val numBlocksToSearch = toBlock - fromBlock
    ???
  }

  /////////////////////
  /////////////////////

  private[jbok] def doCall[A](callTx: CallTx, blockParam: BlockParam): IO[(SignedTransaction, Block)] =
    for {
      stx   <- prepareTransaction(callTx, blockParam)
      block <- resolveBlock(blockParam)
    } yield (stx, block)

  private[jbok] def prepareTransaction(callTx: CallTx, blockParam: BlockParam): IO[SignedTransaction] =
    for {
      gasLimit <- getGasLimit(callTx, blockParam)
      from <- OptionT
        .fromOption[IO](callTx.from.map(Address.apply))
        .getOrElseF(
          EitherT(keyStore.listAccounts).getOrElse(Nil).map(_.head)
        )
      to            = callTx.to.map(Address.apply)
      tx            = Transaction(0, callTx.gasPrice, gasLimit, to, callTx.value, callTx.data)
      fakeSignature = CryptoSignature(0, 0, 0.toByte)
    } yield SignedTransaction(tx, 0.toByte, ByteVector(0), ByteVector(0), from)

  private[jbok] def getGasLimit(callTx: CallTx, blockParam: BlockParam): IO[BigInt] =
    if (callTx.gas.isDefined) {
      callTx.gas.get.pure[IO]
    } else {
      resolveBlock(BlockParam.Latest).map(_.header.gasLimit)
    }

  private[jbok] def removeObsoleteHashrates(now: Date,
                                            rates: Map[ByteVector, (BigInt, Date)]): Map[ByteVector, (BigInt, Date)] =
    rates.filter {
      case (_, (_, reported)) =>
        Duration.between(reported.toInstant, now.toInstant).toMillis < miningConfig.activeTimeout.toMillis
    }

  private[jbok] def reportActive: Response[Unit] = ok {
    val now = new Date()
    lastActive.modify(_ => Some(now)).void
  }

  private[jbok] def resolveAccount(address: Address, blockParam: BlockParam): IO[Account] =
    for {
      block <- resolveBlock(blockParam)
      account <- blockChain
          .getAccount(address, block.header.number)
          .map(_.getOrElse(Account.empty(blockChainConfig.accountStartNonce)))
    } yield account

  private[jbok] def resolveBlock(blockParam: BlockParam): IO[Block] = {
    def getBlock(number: BigInt): IO[Block] = blockChain.getBlockByNumber(number).map(_.get)

    blockParam match {
      case BlockParam.WithNumber(blockNumber) => getBlock(blockNumber)
      case BlockParam.Earliest                => getBlock(0)
      case BlockParam.Latest                  => blockChain.getBestBlockNumber >>= getBlock
      case BlockParam.Pending =>
        OptionT(blockGenerator.getPending.map(_.map(_.block))).getOrElseF(resolveBlock(BlockParam.Latest))
    }
  }
}
