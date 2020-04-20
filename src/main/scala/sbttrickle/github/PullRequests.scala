/*
 * Copyright 2020 Daniel Sobral
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sbttrickle.github

import org.http4s.Uri

import cats.data.EitherT
import cats.effect.{ContextShift, IO, Sync}
import cats.effect.IO.contextShift
import cats.implicits._
import github4s.Github
import github4s.domain.{Pagination, PRFilter, PRFilterOpen, PullRequest}
import github4s.GithubResponses.{GHException, GHResult}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.higherKinds
import scala.util.Try
import scala.util.matching.Regex

import sbttrickle.git.OwnerAndRepository

object PullRequests {
  implicit private val IOContextShift: ContextShift[IO] = contextShift(global)

  private val onlyOpen: List[PRFilter] = List(PRFilterOpen)

  def isPullRequestInProgress(repositoryURL: String,
                              token: String,
                              isAutobumpPullRequest: PullRequest => Boolean): Boolean = {
    val result = for {
      (owner, repo) <- ownerAndRepository(repositoryURL)
      pullRequests <- listPullRequests(token, owner, repo)
    } yield pullRequests.exists(isAutobumpPullRequest)

    result.unsafeRunSync()
  }

  private def ownerAndRepository(repositoryURL: String): IO[(String, String)] = {
    IO.fromEither(OwnerAndRepository(repositoryURL).toRight(makeException(repositoryURL)))
  }

  private def makeException(repositoryURL: String): Exception = {
    new Exception(s"Unable to extract owner and repository name from '$repositoryURL'")
  }

  private def listPullRequests(token: String, owner: String, repo: String): IO[List[PullRequest]] = {
    autoPaginate { pagination =>
      Github[IO](Some(token)).pullRequests.listPullRequests(owner, repo, onlyOpen, Some(pagination))
    }.value.flatMap(IO.fromEither)
  }

  // Based on https://github.com/BenFradet/dashing/blob/e452e9c1d7032ec8199ed9de370680be945a16ee/server/src/main/scala/dashing/server/utils.scala#L112-L135
  // FIXME: pre-computed last page might change
  private def autoPaginate[F[_]: Sync, T]
      (call: Pagination => F[Either[GHException, GHResult[List[T]]]]): EitherT[F, GHException, List[T]] = {
    for {
      firstPage <- EitherT(call(Pagination(1, 100)))
      pages = getPages(firstPage).map(Pagination(_, 100))
      restPages <- EitherT(pages.traverse(call(_)).map(_.sequence))
    } yield firstPage.result ++ restPages.flatMap(_.result)
  }

  private def getPages[T, F[_] : Sync](firstPage: GHResult[List[T]]): List[Int] = {
    getNrPages(firstPage.headers) match {
      case Some(n) if n >= 2 => (2 to n).toList
      case _                 => Nil
    }
  }

  private final case class Relation(name: String, url: String)

  def getNrPages(headers: Map[String, String]): Option[Int] = {
    for {
      h <- headers.map { case (k, v) => k.toLowerCase -> v }.get("link")
      relations = h.split(", ").flatMap {
        case relPattern(url, name) => Some(Relation(name, url))
        case _                     => None
      }
      lastRelation <- relations.find(_.name == "last")
      uri <- Uri.fromString(lastRelation.url).toOption
      lastPage <- uri.params.get("page")
      nrPages <- Try(lastPage.toInt).toOption
    } yield nrPages
  }

  private val relPattern: Regex = """<(.*?)>; rel="(\w+)"""".r
}
