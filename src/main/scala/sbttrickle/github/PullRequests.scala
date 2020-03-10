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

import cats.effect.{ContextShift, IO}
import cats.effect.IO.contextShift
import github4s.Github
import github4s.domain.{PRFilter, PRFilterOpen, PullRequest}
import github4s.GithubResponses.{GHResponse, GHResult}

import scala.concurrent.ExecutionContext.Implicits.global

import sbt.Logger

import sbttrickle.git.OwnerAndRepository

object PullRequests {
  implicit private val IOContextShift: ContextShift[IO] = contextShift(global)

  private val onlyOpen: List[PRFilter] = List(PRFilterOpen)

  def isPullRequestInProgress(repositoryURL: String,
                              token: String,
                              isAutobumpPullRequest: PullRequest => Boolean,
                              log: Logger): Boolean = {
    val result = for {
      (owner, repo) <- OwnerAndRepository(repositoryURL).toRight(makeException(repositoryURL))
      GHResult(pullRequests, _, _) <- listPullRequests(token, owner, repo)
    } yield pullRequests.exists(isAutobumpPullRequest)

    result match {
      case Right(flag)     => flag
      case Left(exception) => throw exception
    }
  }

  private def makeException(repositoryURL: String): Exception = {
    new Exception(s"Unable to extract owner and repository name from '$repositoryURL'")
  }

  // FIXME: github4s doesn't auto-paginate
  private def listPullRequests(token: String, owner: String, repo: String): GHResponse[List[PullRequest]] = {
    Github[IO](Some(token))
      .pullRequests
      .listPullRequests(owner, repo, onlyOpen).unsafeRunSync()
  }

  private implicit class FilterableEither[E, T](x: Either[E, T]) {
    def withFilter(p: T => Boolean): Either[E, T] = x
  }
}
