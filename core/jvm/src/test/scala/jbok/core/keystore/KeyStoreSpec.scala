package jbok.core.keystore

import java.security.SecureRandom

import better.files._
import cats.effect.IO
import jbok.JbokSpec
import jbok.core.keystore.KeyStoreError._
import jbok.core.models.Address
import jbok.crypto.signature.KeyPair
import scodec.bits._

trait KeyStoreFixture {
  val secureRandom = new SecureRandom()
  val dir          = File.newTemporaryDirectory().deleteOnExit()

  val key1     = hex"7a44789ed3cd85861c0bbf9693c7e1de1862dd4396c390147ecf1275099c6e6f"
  val addr1    = Address(hex"aa6826f00d01fe4085f0c3dd12778e206ce4e2ac")
  val keyStore = KeyStorePlatform[IO](dir.pathAsString, secureRandom).unsafeRunSync()
}

class KeyStoreSpec extends JbokSpec {
  "key store" should {
    "import and list accounts" in new KeyStoreFixture {
      val listBeforeImport = keyStore.listAccounts.unsafeRunSync()
      listBeforeImport shouldBe Nil

      // We sleep between imports so that dates of key files' names are different
      val res1 = keyStore.importPrivateKey(key1, "aaa").unsafeRunSync()

      res1 shouldBe addr1

      val listAfterImport = keyStore.listAccounts.unsafeRunSync()
      // result should be ordered by creation date
      listAfterImport shouldBe List(addr1)
    }

    "create new accounts" in new KeyStoreFixture {
      val newAddr1 = keyStore.newAccount("aaa").unsafeRunSync()
      val newAddr2 = keyStore.newAccount("bbb").unsafeRunSync()

      val listOfNewAccounts = keyStore.listAccounts.unsafeRunSync()
      listOfNewAccounts.toSet shouldBe Set(newAddr1, newAddr2)
      listOfNewAccounts.length shouldBe 2
    }

    "return an error when the keystore dir cannot be initialized" in new KeyStoreFixture {
      intercept[IllegalArgumentException] {
        KeyStorePlatform[IO]("/root/keystore", secureRandom).unsafeRunSync()
      }
    }

    "return an error when the keystore dir cannot be read or written" in new KeyStoreFixture {
      dir.delete()

      val key  = hex"7a44789ed3cd85861c0bbf9693c7e1de1862dd4396c390147ecf1275099c6e6f"
      val res1 = keyStore.importPrivateKey(key, "aaa").attempt.unsafeRunSync()
      res1 should matchPattern { case Left(IOError(_)) => }

      val res2 = keyStore.newAccount("aaa").attempt.unsafeRunSync()
      res2 should matchPattern { case Left(IOError(_)) => }

      val res3 = keyStore.listAccounts.attempt.unsafeRunSync()
      res3 should matchPattern { case Left(IOError(_)) => }

      val res4 = keyStore.deleteWallet(Address(key)).attempt.unsafeRunSync()
      res4 should matchPattern { case Left(IOError(_)) => }
    }

    "unlock an account provided a correct passphrase" in new KeyStoreFixture {
      val passphrase = "aaa"
      keyStore.importPrivateKey(key1, passphrase).unsafeRunSync()
      val wallet = keyStore.unlockAccount(addr1, passphrase).unsafeRunSync()
      wallet shouldBe Wallet(addr1, KeyPair.Secret(key1))
    }

    "return an error when unlocking an account with a wrong passphrase" in new KeyStoreFixture {
      keyStore.importPrivateKey(key1, "aaa").unsafeRunSync()
      val res = keyStore.unlockAccount(addr1, "bbb").attempt.unsafeRunSync()
      res shouldBe Left(DecryptionFailed)
    }

    "return an error when trying to unlock an unknown account" in new KeyStoreFixture {
      val res = keyStore.unlockAccount(addr1, "bbb").attempt.unsafeRunSync()
      res shouldBe Left(KeyNotFound)
    }

    "return an error deleting not existing wallet" in new KeyStoreFixture {
      val res = keyStore.deleteWallet(addr1).attempt.unsafeRunSync()
      res shouldBe Left(KeyNotFound)
    }

    "delete existing wallet " in new KeyStoreFixture {
      val newAddr1          = keyStore.newAccount("aaa").unsafeRunSync()
      val listOfNewAccounts = keyStore.listAccounts.unsafeRunSync()
      listOfNewAccounts.toSet shouldBe Set(newAddr1)

      val res = keyStore.deleteWallet(newAddr1).unsafeRunSync()
      res shouldBe true

      val listOfNewAccountsAfterDelete = keyStore.listAccounts.unsafeRunSync()
      listOfNewAccountsAfterDelete.toSet shouldBe Set.empty
    }

    "change passphrase of an existing wallet" in new KeyStoreFixture {
      val oldPassphrase = "weakpass"
      val newPassphrase = "very5tr0ng&l0ngp4s5phr4s3"

      keyStore.importPrivateKey(key1, oldPassphrase).unsafeRunSync()
      keyStore.changePassphrase(addr1, oldPassphrase, newPassphrase).unsafeRunSync() shouldBe true

      keyStore.unlockAccount(addr1, newPassphrase).unsafeRunSync() shouldBe Wallet(addr1, KeyPair.Secret(key1))
    }

    "return an error when changing passphrase of an non-existent wallet" in new KeyStoreFixture {
      keyStore.changePassphrase(addr1, "oldpass", "newpass").attempt.unsafeRunSync() shouldBe Left(KeyNotFound)
    }

    "return an error when changing passphrase and provided with invalid old passphrase" in new KeyStoreFixture {
      keyStore.importPrivateKey(key1, "oldpass").unsafeRunSync()
      keyStore.changePassphrase(addr1, "wrongpass", "newpass").attempt.unsafeRunSync() shouldBe Left(DecryptionFailed)
    }
  }
}
