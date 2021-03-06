package org.simple.clinic.security.pin

import com.f2prateek.rx.preferences2.Preference
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.rxkotlin.Observables
import org.simple.clinic.security.pin.BruteForceProtection.ProtectedState.Allowed
import org.simple.clinic.security.pin.BruteForceProtection.ProtectedState.Blocked
import org.simple.clinic.util.Just
import org.simple.clinic.util.None
import org.simple.clinic.util.Optional
import org.threeten.bp.Clock
import org.threeten.bp.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named

class BruteForceProtection @Inject constructor(
    private val clock: Clock,
    private val configProvider: Single<BruteForceProtectionConfig>,
    @Named("pin_failed_auth_count") private val failedAuthCountPreference: Preference<Int>,
    @Named("pin_failed_auth_limit_reached_at") private val limitReachedAtPreference: Preference<Optional<Instant>>
) {

  companion object {

    fun defaultFailedAttemptsCount() = 0

    fun defaultAttemptsReachedAtTime(): Optional<Instant> = None
  }

  sealed class ProtectedState {
    data class Allowed(val attemptsMade: Int, val attemptsRemaining: Int) : ProtectedState()
    data class Blocked(val attemptsMade: Int, val blockedTill: Instant) : ProtectedState()
  }

  fun incrementFailedAttempt(): Completable {
    return configProvider
        .flatMapCompletable { config ->
          Completable.fromAction {
            val newCount = failedAuthCountPreference.get() + 1
            failedAuthCountPreference.set(newCount)

            val isLimitReached = newCount >= config.limitOfFailedAttempts
            if (isLimitReached && limitReachedAtPreference.get() is None) {
              limitReachedAtPreference.set(Just(Instant.now(clock)))
            } else {
              limitReachedAtPreference.set(limitReachedAtPreference.get())
            }
          }
        }
  }

  /** Just a proxy function, but the name makes more sense. */
  fun recordSuccessfulAuthentication(): Completable {
    return resetFailedAttempts()
  }

  fun resetFailedAttempts(): Completable {
    return Completable.fromAction {
      failedAuthCountPreference.delete()
      limitReachedAtPreference.delete()
    }
  }

  fun protectedStateChanges(): Observable<ProtectedState> {
    val autoResets = Observables.combineLatest(configProvider.toObservable(), limitReachedAtPreference.asObservable())
        .switchMap { (config) ->
          val (blockedAt: Instant?) = limitReachedAtPreference.get()
          if (blockedAt == null) {
            Observable.empty()

          } else {
            val blockExpiresAt = blockedAt + config.blockDuration
            val now = Instant.now(clock)

            // It's possible that the block duration gets updated by a config update
            // from the server, potentially resulting in a situation where the expiry
            // time is now in the past.
            val millisTillExpiry = Math.max(blockExpiresAt.toEpochMilli() - now.toEpochMilli(), 0)
            Observable.timer(millisTillExpiry + 1, TimeUnit.MILLISECONDS)
          }
        }
        .flatMapCompletable { resetFailedAttempts() }
        .toObservable<Any>()

    val alwaysAllowWhenDisabled = Observables
        .combineLatest(configProvider.toObservable(), failedAuthCountPreference.asObservable())
        .filter { (config) -> config.isEnabled.not() }
        .map { Allowed(attemptsMade = Math.min(1, failedAuthCountPreference.get()), attemptsRemaining = 1) }

    // Using zip to avoid transient notifications midway during updating
    // both preferences. RxPreferences doesn't support bulk modifications.
    val preferenceChanges = Observables.zip(limitReachedAtPreference.asObservable(), failedAuthCountPreference.asObservable())

    return Observables.combineLatest(configProvider.toObservable(), preferenceChanges, autoResets.startWith(Any()))
        .filter { (config) -> config.isEnabled }
        .map { (config) ->
          val (blockedAt: Instant?) = limitReachedAtPreference.get()
          val attemptsMade = failedAuthCountPreference.get()

          if (blockedAt == null) {
            val attemptsRemaining = Math.max(0, config.limitOfFailedAttempts - attemptsMade)
            Allowed(attemptsMade = attemptsMade, attemptsRemaining = attemptsRemaining)

          } else {
            Blocked(attemptsMade = attemptsMade, blockedTill = blockedAt + config.blockDuration)
          }
        }
        .distinctUntilChanged()
        .mergeWith(alwaysAllowWhenDisabled)
  }
}
