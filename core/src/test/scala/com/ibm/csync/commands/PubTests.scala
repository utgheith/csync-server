/*
 * Copyright IBM Corporation 2017
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ibm.csync.commands

import com.ibm.csync.database.Database
import com.ibm.csync.rabbitmq.Factory
import com.ibm.csync.session.Session
import com.ibm.csync.types.ResponseCode.{CannotDeleteNonExistingPath, InvalidPathFormat, PubCtsCheckFailed}
import com.ibm.csync.types.{ClientError, Key, SessionId, Token}
import org.postgresql.ds.PGPoolingDataSource
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FunSuite, Matchers}

import scala.collection.mutable
import scala.concurrent.{Future, Promise}

class PubTests extends FunSuite with Matchers with ScalaFutures {
  // scalastyle:off magic.number
  def fakeSession(f: Response => Future[_]): Session = {

    val ds = new PGPoolingDataSource()
    ds.setCurrentSchema("pg_temp")
    ds.setServerName("localhost")
    ds.setMaxConnections(1)
    Database.createTables(ds)

    val rabbit = Factory("amqp://guest:guest@localhost:5672/testing").newConnection

    Session(ds, "", rabbit, Some("demo"),
      Token("demoToken"), SessionId())(f)
  }

  test("Publish a Parent and its child") {

    val promise = Promise[Map[Key, Data]]()
    val responseData = mutable.Map[Key, Data]()
    val session = fakeSession { outgoing =>
      outgoing match {
        case d: Data =>
          val key = Key(d.path)
          responseData(key) = d
          if (responseData.keySet.size == 2) promise.success(responseData.toMap)
        case _ =>
      }
      Future.successful(())
    }
    try {
      Sub(Seq("#")).doit(session)
      val aPubResponse = Pub(99, Seq("a"), Some("x"), false, None, None).doit(session)
      val bPubResponse = Pub(100, Seq("a", "b"), Some("z"), false, None, None).doit(session)

      //Check pub responses
      aPubResponse.code should be(0)
      aPubResponse.vts should be(2)
      aPubResponse.cts should be(99)

      bPubResponse.code should be(0)
      bPubResponse.vts should be(3)
      bPubResponse.cts should be(100)

      val res = promise.future.futureValue
      res.size should be(2)
      val a = res(Key("a"))
      val b = res(Key("a", "b"))

      // A should have published before b, ensure this happened
      a.vts should be < b.vts
      //Make sure the cts we sent off are correct
      a.cts should be(aPubResponse.cts)
      a.vts should be(aPubResponse.vts)
      a.data should be(Some("x"))
      a.creator should be("demoUser")
      a.acl should be("$publicCreate")
      a.deletePath should be(false)

      b.cts should be(bPubResponse.cts)
      b.vts should be(bPubResponse.vts)
      b.data should be(Some("z"))
      b.creator should be("demoUser")
      b.acl should be("$publicCreate")
      b.deletePath should be(false)
    } finally {
      session.close()
    }
  }

  test("Publish a single node and delete it") {

    val promise = Promise[Map[Key, Data]]()
    val deletePromise = Promise[Map[Key, Data]]()
    val responseData = mutable.Map[Key, Data]()
    var promiseComplete = false
    val session = fakeSession { outgoing =>
      outgoing match {
        case d: Data =>
          val key = Key(d.path)
          responseData(key) = d
          if (responseData.keySet.size == 1 && !promiseComplete) {
            promiseComplete = true
            promise.success(responseData.toMap)
          }
          if (responseData(Key("c")).deletePath) {
            deletePromise.success(responseData.toMap)
          }
        case _ =>
      }
      Future.successful(())
    }
    try {
      Sub(Seq("#")).doit(session)
      val createPubResponse = Pub(101, Seq("c"), Some("x"), false, None, None).doit(session)

      //Check Pub Response
      createPubResponse.code should be(0)
      createPubResponse.vts should be(2)
      createPubResponse.cts should be(101)

      val res = promise.future.futureValue
      res.size should be(1)
      val key = res(Key("c"))

      //Check the key to be sure it is correct
      key.cts should be(createPubResponse.cts)
      key.vts should be(createPubResponse.vts)
      key.data should be(Some("x"))
      key.creator should be("demoUser")
      key.acl should be("$publicCreate")
      key.deletePath should be(false)

      //delete the key
      val deletePubResponse = Pub(102, Seq("c"), Some("x"), true, None, None).doit(session)

      //Check Pub Response
      deletePubResponse.code should be(0)
      deletePubResponse.vts should be(3)
      deletePubResponse.cts should be(102)

      val deleteRes = deletePromise.future.futureValue
      deleteRes.size should be(1)
      val deletedKey = deleteRes(Key("c"))

      //check the new key
      deletedKey.cts should be(deletePubResponse.cts)
      deletedKey.vts should be(deletePubResponse.vts)
      deletedKey.creator should be("demoUser")
      deletedKey.acl should be("$publicCreate")
      deletedKey.deletePath should be(true)
    } finally {
      session.close()
    }
  }

  test("Publish a single node and update it with a different ACL") {

    val promise = Promise[Map[Key, Data]]()
    val updatePromise = Promise[Map[Key, Data]]()
    val responseData = mutable.Map[Key, Data]()
    var promiseComplete = false
    val session = fakeSession { outgoing =>
      outgoing match {
        case d: Data =>
          val key = Key(d.path)
          responseData(key) = d
          if (responseData.keySet.size == 1 && !promiseComplete) {
            promiseComplete = true
            promise.success(responseData.toMap)
          } else if (!responseData(key).deletePath) {
            updatePromise.success(responseData.toMap)
          }
        case _ =>
      }
      Future.successful(())
    }
    try {
      Sub(Seq("#")).doit(session)
      val firstPubResponse = Pub(103, Seq("e"), Some("x"), false, None, None).doit(session)

      //Check Pub Response
      firstPubResponse.code should be(0)
      firstPubResponse.vts should be(2)
      firstPubResponse.cts should be(103)

      val res = promise.future.futureValue
      res.size should be(1)
      val key = res(Key("e"))

      //Check the key to be sure it is correct
      key.cts should be(firstPubResponse.cts)
      key.vts should be(firstPubResponse.vts)
      key.data should be(Some("x"))
      key.creator should be("demoUser")
      key.acl should be("$publicCreate")
      key.deletePath should be(false)

      //pub the key again with changed data
      val secondPubResponse = Pub(104, Seq("e"), Some("y"), false, Option("$publicReadWriteCreate"), None).doit(session)

      //Check Pub Response
      secondPubResponse.code should be(0)
      secondPubResponse.vts should be(4) //VTS increases by two due to changed ACL
      secondPubResponse.cts should be(104)

      val updateRes = updatePromise.future.futureValue
      updateRes.size should be(1)
      val updateKey = updateRes(Key("e"))

      //check the new key
      updateKey.cts should be(secondPubResponse.cts)
      updateKey.vts should be(secondPubResponse.vts)
      updateKey.creator should be("demoUser")
      updateKey.acl should be("$publicReadWriteCreate")
      updateKey.deletePath should be(false)
      updateKey.data should be(Some("y"))
    } finally {
      session.close()
    }
  }

  test("Publish a single node and delete it then publish a replacement") {

    val promise = Promise[Map[Key, Data]]()
    val deletePromise = Promise[Map[Key, Data]]()
    val updatePromise = Promise[Map[Key, Data]]()
    val responseData = mutable.Map[Key, Data]()
    var promiseComplete = false
    val session = fakeSession { outgoing =>
      outgoing match {
        case d: Data =>
          val key = Key(d.path)
          responseData(key) = d
          if (responseData.keySet.size == 1 && !promiseComplete) {
            promiseComplete = true
            promise.success(responseData.toMap)
          } else if (responseData(Key("f")).deletePath) {
            deletePromise.success(responseData.toMap)
          } else {
            updatePromise.success(responseData.toMap)
          }
        case _ =>
      }
      Future.successful(())
    }
    try {
      Sub(Seq("#")).doit(session)
      val firstPubResponse = Pub(105, Seq("f"), Some("x"), false, None, None).doit(session)

      //Check Pub Response
      firstPubResponse.code should be(0)
      firstPubResponse.vts should be(2)
      firstPubResponse.cts should be(105)

      val res = promise.future.futureValue
      res.size should be(1)
      val key = res(Key("f"))

      //Check the key to be sure it is correct
      key.cts should be(firstPubResponse.cts)
      key.vts should be(firstPubResponse.vts)
      key.data should be(Some("x"))
      key.creator should be("demoUser")
      key.acl should be("$publicCreate")
      key.deletePath should be(false)

      //delete the key
      val secondPubResponse = Pub(106, Seq("f"), Some("x"), true, None, None).doit(session)

      //Check Pub Response
      secondPubResponse.code should be(0)
      secondPubResponse.vts should be(3)
      secondPubResponse.cts should be(106)

      val deleteRes = deletePromise.future.futureValue
      deleteRes.size should be(1)
      val deletedKey = deleteRes(Key("f"))
      //check the new key
      deletedKey.cts should be(secondPubResponse.cts)
      deletedKey.vts should be(secondPubResponse.vts)
      deletedKey.creator should be("demoUser")
      deletedKey.acl should be("$publicCreate")
      deletedKey.deletePath should be(true)

      val thirdPubResponse = Pub(107, Seq("f"), Some("z"), false, None, None).doit(session)

      //Check Pub Response
      thirdPubResponse.code should be(0)
      thirdPubResponse.vts should be(4)
      thirdPubResponse.cts should be(107)

      val updateRes = updatePromise.future.futureValue
      updateRes.size should be(1)
      val updateKey = updateRes(Key("f"))

      //check the new key
      updateKey.cts should be(thirdPubResponse.cts)
      updateKey.vts should be(thirdPubResponse.vts)
      updateKey.creator should be("demoUser")
      updateKey.acl should be("$publicCreate")
      updateKey.deletePath should be(false)
      updateKey.data should be(Some("z"))
    } finally {
      session.close()
    }
  }

  test("Delete on a single node that doesn't exist") {

    val promise = Promise[Map[Key, Data]]()
    val responseData = mutable.Map[Key, Data]()
    val session = fakeSession { _ => Future.successful(()) }
    try {
      try {
        //Assert this throws the correct path does not exist error
        Pub(102, Seq("a"), Some("z"), true, None, None).doit(session)
      } catch {
        case e: ClientError => e.code should be(CannotDeleteNonExistingPath)
        case e: Exception => fail()
      }
    } finally {
      session.close()
    }
  }

  test("Delete on a single node that has a newer cts") {

    val session = fakeSession { _ => Future.successful(()) }
    try {
      try {
        Pub(103, Seq("a"), Some("z"), false, None, None).doit(session)
        //Assert this throws the correct CTS check failed error
        Pub(102, Seq("a"), Some("z"), true, None, None).doit(session)
      } catch {
        case e: ClientError => e.code should be(PubCtsCheckFailed)
        case e: Exception => fail()
      }
    } finally {
      session.close()
    }
  }

  //WILDCARD TESTS

  test("Delete on a wildcard at the end") {

    val promise = Promise[Map[Key, Data]]()
    val responseData = mutable.Map[Key, Data]()
    val session = fakeSession { outgoing =>
      outgoing match {
        case d: Data =>
          val key = Key(d.path)
          if (d.deletePath == true) {
            responseData(key) = d
          }
          if (responseData.keySet.size == 2) {
            promise.success(responseData.toMap)
          }
        case _ =>
      }
      Future.successful(())
    }
    try {
      Pub(100, Seq("a", "b"), Some("y"), false, None, None).doit(session)
      Pub(101, Seq("b", "b"), Some("x"), false, None, None).doit(session) //this pub should not be found
      Pub(102, Seq("a", "c"), Some("z"), false, None, None).doit(session)
      Sub(Seq("#")).doit(session)
      val deletePubResponse = Pub(103, Seq("a", "*"), Some("z"), true, None, None).doit(session)
      val res = promise.future.futureValue
      val keyB = res(Key("a", "b"))
      val keyC = res(Key("a", "c"))

      keyB.cts should be(deletePubResponse.cts)
      keyB.vts should be <= deletePubResponse.vts
      keyB.creator should be("demoUser")
      keyB.acl should be("$publicCreate")
      keyB.deletePath should be(true)
      keyB.data should be(None)

      keyC.cts should be(deletePubResponse.cts)
      keyC.vts should be <= deletePubResponse.vts
      keyC.creator should be("demoUser")
      keyC.acl should be("$publicCreate")
      keyC.deletePath should be(true)
      keyC.data should be(None)
    } finally {
      session.close()
    }
  }

  test("Delete on a wildcard in the middle") {

    val promise = Promise[Map[Key, Data]]()
    val responseData = mutable.Map[Key, Data]()
    val session = fakeSession { outgoing =>
      outgoing match {
        case d: Data =>
          val key = Key(d.path)
          if (d.deletePath == true) {
            responseData(key) = d
          }
          if (responseData.keySet.size == 2) {
            promise.success(responseData.toMap)
          }
        case _ =>
      }
      Future.successful(())
    }
    try {
      Pub(100, Seq("a", "b", "f"), Some("y"), false, None, None).doit(session)
      Pub(101, Seq("a", "c", "d"), Some("x"), false, None, None).doit(session) //this pub should not be found
      Pub(102, Seq("a", "c", "f"), Some("z"), false, None, None).doit(session)
      Sub(Seq("#")).doit(session)
      val deletePubResponse = Pub(103, Seq("a", "*", "f"), Some("z"), true, None, None).doit(session)
      val res = promise.future.futureValue
      val keyB = res(Key("a", "b", "f"))
      val keyC = res(Key("a", "c", "f"))

      keyB.cts should be(deletePubResponse.cts)
      keyB.vts should be <= deletePubResponse.vts
      keyB.creator should be("demoUser")
      keyB.acl should be("$publicCreate")
      keyB.deletePath should be(true)
      keyB.data should be(None)

      keyC.cts should be(deletePubResponse.cts)
      keyC.vts should be <= deletePubResponse.vts
      keyC.creator should be("demoUser")
      keyC.acl should be("$publicCreate")
      keyC.deletePath should be(true)
      keyC.data should be(None)
    } finally {
      session.close()
    }
  }

  test("Delete on a wildcard where one key is newer") {

    val promise = Promise[Map[Key, Data]]()
    val responseData = mutable.Map[Key, Data]()
    val session = fakeSession { outgoing =>
      outgoing match {
        case d: Data =>
          val key = Key(d.path)
          if (d.deletePath == true) {
            responseData(key) = d
          }
          if (responseData.keySet.size == 2) {
            promise.success(responseData.toMap)
          }
        case _ =>
      }
      Future.successful(())
    }
    try {
      Pub(100, Seq("a", "b"), Some("y"), false, None, None).doit(session)
      Pub(103, Seq("a", "c"), Some("x"), false, None, None).doit(session) //this pub should not be deleted
      Pub(101, Seq("a", "d"), Some("z"), false, None, None).doit(session)
      Sub(Seq("#")).doit(session)
      val deletePubResponse = Pub(102, Seq("a", "*"), Some("z"), true, None, None).doit(session)
      val res = promise.future.futureValue
      val keyB = res(Key("a", "b"))
      val keyD = res(Key("a", "d"))

      keyB.cts should be(deletePubResponse.cts)
      keyB.vts should be <= deletePubResponse.vts
      keyB.creator should be("demoUser")
      keyB.acl should be("$publicCreate")
      keyB.deletePath should be(true)
      keyB.data should be(None)

      keyD.cts should be(deletePubResponse.cts)
      keyD.vts should be <= deletePubResponse.vts
      keyD.creator should be("demoUser")
      keyD.acl should be("$publicCreate")
      keyD.deletePath should be(true)
      keyD.data should be(None)
    } finally {
      session.close()
    }
  }

  test("Delete on a wildcard where nodes don't exist") {

    val session = fakeSession { _ => Future.successful(()) }
    try {
      val deleteResponse = Pub(102, Seq("a", "*"), Some("z"), true, None, None).doit(session)
      deleteResponse.vts should be(0)
      deleteResponse.cts should be(102)
    } finally {
      session.close()
    }
  }

  test("Wildcard delete on a single newer node") {

    val session = fakeSession { _ => Future.successful(()) }
    try {
      Pub(103, Seq("a", "b"), Some("z"), false, None, None).doit(session)
      val deleteResponse = Pub(102, Seq("a", "*"), Some("z"), true, None, None).doit(session)
      deleteResponse.vts should be(0)
      deleteResponse.cts should be(102)
    } finally {
      session.close()
    }
  }

  test("Should not be able to pub create on a wildcard") {

    val session = fakeSession { _ => Future.successful(()) }
    try {
      try {
        Pub(103, Seq("a", "b", "*"), Some("z"), false, None, None).doit(session)
      } catch {
        case e: ClientError => e.code should be(InvalidPathFormat)
        case e: Exception => fail()
      }
    } finally {
      session.close()
    }
  }
  // scalastyle:on magic.number
}
