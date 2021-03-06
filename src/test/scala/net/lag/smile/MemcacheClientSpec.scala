/*
 * Copyright 2009 Twitter, Inc.
 * Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lag.smile

import _root_.org.specs._
import _root_.scala.collection.mutable
import _root_.java.util.concurrent.CountDownLatch


object MemcacheClientSpec extends Specification {

  var pool: ServerPool = null
  val servers = new mutable.ListBuffer[FakeMemcacheConnection]
  var client: MemcacheClient[String] = null

  def makeServers(seed: List[List[Task]]) = {
    // silly node locator that chooses based on the first letter of the key.
    val locator = new NodeLocator {
      var pool: ServerPool = null

      def setPool(pool: ServerPool) = {
        this.pool = pool
      }

      def findNode(key: Array[Byte]) = {
        val n = (key(0) - 'a'.toByte) % pool.servers.size
        pool.servers(n)
      }
    }

    val connections = new mutable.ListBuffer[MemcacheConnection]
    pool = new ServerPool
    for (tasks <- seed) {
      val server = new FakeMemcacheConnection(tasks)
      server.start
      servers += server
      val connection = new MemcacheConnection("localhost", server.port, 1)
      connection.pool = pool
      connections += connection
    }
    pool.servers = connections.toArray
    client = new MemcacheClient(locator, MemcacheCodec.UTF8)
    client.setPool(pool)
  }

  "MemcacheClient" should {
    doAfter {
      for (s <- servers) {
        s.stop
      }
      servers.clear()
      client.shutdown()
    }


    "get keys from 3 different servers" in {
      makeServers(List(
        Receive(7) :: Send("VALUE a 0 5\r\napple\r\nEND\r\n".getBytes) :: Nil,
        Receive(7) :: Send("VALUE b 0 5\r\nbeach\r\nEND\r\n".getBytes) :: Nil,
        Receive(7) :: Send("VALUE c 0 5\r\nconch\r\nEND\r\n".getBytes) :: Nil
      ))
      client.get("a") mustEqual Some("apple")
      client.get("b") mustEqual Some("beach")
      client.get("c") mustEqual Some("conch")
      for (s <- servers) {
        s.awaitConnection(500) mustBe true
      }
      servers(0).fromClient mustEqual List("get a\r\n")
      servers(1).fromClient mustEqual List("get b\r\n")
      servers(2).fromClient mustEqual List("get c\r\n")
    }

    "get keys from 3 different servers simultaneously" in {
      makeServers(List(
        Receive(7) :: Sleep(100) :: Send("VALUE a 0 5\r\napple\r\nEND\r\n".getBytes) :: Nil,
        Receive(7) :: Sleep(100) :: Send("VALUE b 0 5\r\nbeach\r\nEND\r\n".getBytes) :: Nil,
        Receive(7) :: Sleep(100) :: Send("VALUE c 0 5\r\nconch\r\nEND\r\n".getBytes) :: Nil
      ))

      val latch = new CountDownLatch(1)
      var val1: Option[String] = None
      var val2: Option[String] = None
      var val3: Option[String] = None
      val t1 = new Thread {
        override def run = {
          latch.await
          val1 = client.get("a")
        }
      }
      val t2 = new Thread {
        override def run = {
          latch.await
          val2 = client.get("b")
        }
      }
      val t3 = new Thread {
        override def run = {
          latch.await
          val3 = client.get("c")
        }
      }

      t1.start
      t2.start
      t3.start
      latch.countDown
      t1.join
      t2.join
      t3.join

      val1 mustEqual Some("apple")
      val2 mustEqual Some("beach")
      val3 mustEqual Some("conch")
      for (s <- servers) {
        s.awaitConnection(500) mustBe true
      }
      servers(0).fromClient mustEqual List("get a\r\n")
      servers(1).fromClient mustEqual List("get b\r\n")
      servers(2).fromClient mustEqual List("get c\r\n")
    }

    "multi-get keys from 3 different servers" in {
      makeServers(List(
        Receive(7) :: Send("VALUE a 0 5\r\napple\r\nEND\r\n".getBytes) :: Nil,
        Receive(7) :: Send("VALUE b 0 5\r\nbeach\r\nEND\r\n".getBytes) :: Nil,
        Receive(7) :: Send("VALUE c 0 5\r\nconch\r\nEND\r\n".getBytes) :: Nil
      ))
      client.get(Array("a", "b", "c")) mustEqual Map("a" -> "apple", "b" -> "beach", "c" -> "conch")
      for (s <- servers) {
        s.awaitConnection(500) mustBe true
      }
      servers(0).fromClient mustEqual List("get a\r\n")
      servers(1).fromClient mustEqual List("get b\r\n")
      servers(2).fromClient mustEqual List("get c\r\n")
    }

    "multi-get keys from 1 server with namespacing" in {
      makeServers(List(
        Receive(17) :: Send(("VALUE a:a 0 5\r\napple\r\nVALUE a:b 0 5\r\n" +
          "beach\r\nVALUE a:c 0 5\r\nconch\r\nEND\r\n").getBytes) :: Nil
        ))
      client.namespace = Some("a:")
      client.get(Array("a", "b", "c")) mustEqual Map("a" -> "apple", "b" -> "beach", "c" -> "conch")
      for (s <- servers) {
        s.awaitConnection(500) mustBe true
      }
      servers(0).fromClient mustEqual List("get a:a a:b a:c\r\n")
    }

    "reject keys that are too long" in {
      makeServers(List(Nil))
      client.get("x" * 300) must throwA(new KeyTooLongException)
    }

    "recover from a multi-get if one of the servers is down" in {
      makeServers(List(
        Receive(7) :: Send("VALUE a 0 5\r\napple\r\nEND\r\n".getBytes) :: Nil,
        KillListenSocket :: Nil
      ))
      client.get(Array("a", "b")) mustEqual Map("a" -> "apple")
    }
  }
}
