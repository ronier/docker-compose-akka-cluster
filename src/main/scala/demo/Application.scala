package demo

import java.io.File

import akka.actor._
import akka.cluster.Cluster
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object Application extends App {
  val SystemName = "docker-cluster"
  val workerNetwork = "0.0.0.0"

  val AKKA_PORT = "akka.remote.netty.tcp.port"
  val AKKA_HOST = "akka.remote.netty.tcp.hostname"

  val sysPropSeedPort = "seedPort"
  val sysPropsSeedHost = "seedHost"
  val sysPropsHttpPort = "httpPort"
  val sysPropsSeedHostForWorker = "seedHostToConnect"

  val port = sys.props.get(sysPropSeedPort).fold(throw new Exception(s"Couldn't find $AKKA_PORT system property"))(identity)
  val hostName = sys.props.get(sysPropsSeedHost).getOrElse(workerNetwork)
  val seedNode = hostName ne (workerNetwork)

  private def createConfig(isSeed: Boolean) = {
    val overrideConfig = if (isSeed) {
      ConfigFactory.empty()
        .withFallback(ConfigFactory.parseString(s"$AKKA_HOST=$hostName"))
        .withFallback(ConfigFactory.parseString(s"$AKKA_PORT=$port"))
    } else {
      ConfigFactory.empty()
        .withFallback(ConfigFactory.parseString(s"$AKKA_PORT=$port"))
    }
    overrideConfig.withFallback(ConfigFactory.load())
  }

  val cfg = createConfig(seedNode)
  implicit val system = ActorSystem(SystemName, cfg)
  implicit val mat = ActorMaterializer()
  implicit val _ = mat.executionContext

  val cluster = Cluster(system)
  val log = system.log

  if (seedNode) {
    log.info("seed-node.conf exists:{}", new File("/opt/docker/seed-node.conf").exists)

    val address = Address("akka.tcp", SystemName, hostName, port.toInt)
    log.info("seed-node is being joined to itself {}", address)
    cluster.joinSeedNodes(immutable.Seq(address))
    val httpPort = sys.props.get(sysPropsHttpPort).fold(throw new Exception(s"Couldn't find $sysPropsHttpPort system property"))(identity)

    Http().bindAndHandle(new HttpRoutes(cluster).route, interface = cluster.selfAddress.host.get, port = httpPort.toInt)
      .onComplete {
        case Success(r) =>
          val jmxPort = sys.props.get("com.sun.management.jmxremote.port")
          log.info(s"* * * http-server:${r.localAddress} host:${cfg.getString(AKKA_HOST)} akka-port:${cfg.getInt(AKKA_PORT)} JMX-port:$jmxPort * * *")
        case Failure(ex) =>
          system.log.error(ex, "Couldn't bind http server")
          System.exit(-1)
      }
  } else {
    log.info("worker-node.conf exists:{}", new File("/opt/docker/worker-node.conf").exists)
    val seed = sys.props.get(sysPropsSeedHostForWorker).fold(throw new Exception(s"Couldn't find $sysPropsSeedHostForWorker system property"))(identity)
    val seedAddress = Address("akka.tcp", SystemName, seed, port.toInt)
    log.info(s"worker node joined seed {}", seedAddress)
    cluster.joinSeedNodes(immutable.Seq(seedAddress))
    system.log.info(s"* * * host:${cfg.getString(AKKA_HOST)} akka-port:${cfg.getInt(AKKA_PORT)} * * *")
  }

  sys.addShutdownHook {
    Await.ready(system.terminate, 5 seconds)
    system.log.info("Node {} has been removed from the cluster", cluster.selfAddress)
    cluster.leave(cluster.selfAddress)
  }
}