// Amanuensis - a local web proxy for debugging
// Copyright (C) 2023 Benjamin Bader
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package com.bendb.ama.proxy

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@JvmInline
value class SessionId(val id: ULong)

interface Session : SuspendCloseable {
    val id: SessionId
    fun run(): Flow<SessionEvent>
}

object EmptySession : Session {
    override val id: SessionId = SessionId(0UL)
    override fun run(): Flow<SessionEvent> = emptyFlow()
    override suspend fun close() {}
}

sealed class SessionEvent(val session: Session) {
    class Started(session: Session) : SessionEvent(session)
    class TransactionStarted(session: Session, val transaction: Transaction): SessionEvent(session)
    class Error(session: Session, val error: Throwable): SessionEvent(session)
    class Stopped(session: Session) : SessionEvent(session)
}

