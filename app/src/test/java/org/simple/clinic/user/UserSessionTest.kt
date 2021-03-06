package org.simple.clinic.user

import android.content.SharedPreferences
import com.f2prateek.rx.preferences2.Preference
import com.google.common.truth.Truth.assertThat
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.check
import com.nhaarman.mockito_kotlin.doThrow
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.never
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.verifyZeroInteractions
import com.nhaarman.mockito_kotlin.whenever
import com.squareup.moshi.Moshi
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import junitparams.JUnitParamsRunner
import junitparams.Parameters
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.simple.clinic.AppDatabase
import org.simple.clinic.analytics.Analytics
import org.simple.clinic.analytics.MockAnalyticsReporter
import org.simple.clinic.facility.FacilityPullResult
import org.simple.clinic.facility.FacilityRepository
import org.simple.clinic.facility.FacilitySync
import org.simple.clinic.forgotpin.ForgotPinResponse
import org.simple.clinic.forgotpin.ResetPinRequest
import org.simple.clinic.login.LoginApiV1
import org.simple.clinic.login.LoginOtpSmsListener
import org.simple.clinic.login.LoginResponse
import org.simple.clinic.login.LoginResult
import org.simple.clinic.patient.PatientMocker
import org.simple.clinic.patient.PatientRepository
import org.simple.clinic.registration.FindUserResult
import org.simple.clinic.registration.RegistrationApiV1
import org.simple.clinic.registration.RegistrationResponse
import org.simple.clinic.registration.RegistrationResult
import org.simple.clinic.registration.SaveUserLocallyResult
import org.simple.clinic.security.PasswordHasher
import org.simple.clinic.security.pin.BruteForceProtection
import org.simple.clinic.sync.SyncScheduler
import org.simple.clinic.util.Just
import org.simple.clinic.util.Optional
import org.threeten.bp.Instant
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.util.UUID

@RunWith(JUnitParamsRunner::class)
class UserSessionTest {

  private val loginApi = mock<LoginApiV1>()
  private val registrationApi = mock<RegistrationApiV1>()
  private val accessTokenPref = mock<Preference<Optional<String>>>()
  private val facilitySync = mock<FacilitySync>()
  private val facilityRepository = mock<FacilityRepository>()
  private val patientRepository = mock<PatientRepository>()
  private val sharedPrefs = mock<SharedPreferences>()
  private val appDatabase = mock<AppDatabase>()
  private val passwordHasher = mock<PasswordHasher>()
  private val userDao = mock<User.RoomDao>()
  private val reporter = MockAnalyticsReporter()
  private val ongoingLoginEntryRepository = mock<OngoingLoginEntryRepository>()
  private var bruteForceProtection = mock<BruteForceProtection>()

  private val moshi = Moshi.Builder().build()
  private val loggedInUserPayload = PatientMocker.loggedInUserPayload()
  private val unauthorizedErrorResponseJson = """{
        "errors": {
          "user": [
            "user is not present"
          ]
        }
      }"""

  private lateinit var userSession: UserSession
  private lateinit var syncScheduler: SyncScheduler

  private lateinit var medicalHistoryPullTimestamp: Preference<Optional<Instant>>
  private lateinit var communicationPullTimestamp: Preference<Optional<Instant>>
  private lateinit var appointmentPullTimestamp: Preference<Optional<Instant>>
  private lateinit var prescriptionPullTimestamp: Preference<Optional<Instant>>
  private lateinit var bpPullTimestamp: Preference<Optional<Instant>>
  private lateinit var patientPullTimestamp: Preference<Optional<Instant>>
  private lateinit var loginOtpSmsListener: LoginOtpSmsListener

  @Before
  fun setUp() {
    // Used for overriding IO scheduler for sync call on login.
    RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }

    loginOtpSmsListener = mock()
    syncScheduler = mock()
    medicalHistoryPullTimestamp = mock()
    communicationPullTimestamp = mock()
    appointmentPullTimestamp = mock()
    prescriptionPullTimestamp = mock()
    bpPullTimestamp = mock()
    patientPullTimestamp = mock()

    userSession = UserSession(
        loginApi = loginApi,
        registrationApi = registrationApi,
        moshi = moshi,
        facilitySync = facilitySync,
        facilityRepository = facilityRepository,
        sharedPreferences = sharedPrefs,
        appDatabase = appDatabase,
        passwordHasher = passwordHasher,
        syncScheduler = syncScheduler,
        loginOtpSmsListener = loginOtpSmsListener,
        accessTokenPreference = accessTokenPref,
        bruteForceProtection = bruteForceProtection,
        patientSyncPullTimestamp = patientPullTimestamp,
        bpSyncPullTimestamp = bpPullTimestamp,
        prescriptionSyncPullTimestamp = prescriptionPullTimestamp,
        appointmentSyncPullTimestamp = appointmentPullTimestamp,
        communicationSyncPullTimestamp = communicationPullTimestamp,
        medicalHistorySyncPullTimestamp = medicalHistoryPullTimestamp,
        ongoingLoginEntryRepository = ongoingLoginEntryRepository)

    whenever(ongoingLoginEntryRepository.saveLoginEntry(any())).thenReturn(Completable.complete())
    whenever(facilitySync.sync()).thenReturn(Completable.complete())
    whenever(patientRepository.clearPatientData()).thenReturn(Completable.complete())
    whenever(appDatabase.userDao()).thenReturn(userDao)
    whenever(facilityRepository.associateUserWithFacilities(any(), any(), any())).thenReturn(Completable.complete())
    whenever(ongoingLoginEntryRepository.entry()).thenReturn(Single.just(OngoingLoginEntry(uuid = UUID.randomUUID(), phoneNumber = "982312441")))
    whenever(bruteForceProtection.resetFailedAttempts()).thenReturn(Completable.complete())

    userSession.saveOngoingLoginEntry(OngoingLoginEntry(UUID.randomUUID(), "phone", "pin")).blockingAwait()

    Analytics.addReporter(reporter)
  }

  private fun <T> unauthorizedHttpError(): HttpException {
    val error = Response.error<T>(401, ResponseBody.create(MediaType.parse("text"), unauthorizedErrorResponseJson))
    return HttpException(error)
  }

  @Test
  @Parameters(method = "params for mapping network response for login")
  fun `login should correctly map network response to result`(
      response: Single<LoginResponse>,
      expectedResultType: Class<LoginResult>
  ) {
    whenever(loginApi.login(any())).thenReturn(response)
    whenever(syncScheduler.syncImmediately()).thenReturn(Completable.complete())
    whenever(ongoingLoginEntryRepository.clearLoginEntry()).thenReturn(Completable.complete())

    val result = userSession.loginWithOtp("000000").blockingGet()

    assertThat(result).isInstanceOf(expectedResultType)
  }

  @Suppress("Unused")
  private fun `params for mapping network response for login`(): List<List<Any>> {
    return listOf(
        listOf(Single.just(LoginResponse("accessToken", loggedInUserPayload)), LoginResult.Success::class.java),
        listOf(Single.error<LoginResponse>(NullPointerException()), LoginResult.UnexpectedError::class.java),
        listOf(Single.error<LoginResponse>(unauthorizedHttpError<LoginResponse>()), LoginResult.ServerError::class.java),
        listOf(Single.error<LoginResponse>(IOException()), LoginResult.NetworkError::class.java)
    )
  }

  @Test
  @Parameters(method = "parameters for triggering sync on login")
  fun `sync should only be triggered on successful login`(
      response: Single<LoginResponse>,
      shouldTriggerSync: Boolean
  ) {
    var syncInvoked = false

    whenever(loginApi.login(any())).thenReturn(response)
    whenever(syncScheduler.syncImmediately()).thenReturn(Completable.complete().doOnComplete { syncInvoked = true })

    userSession.loginWithOtp("000000").blockingGet()

    if (shouldTriggerSync) {
      assertThat(syncInvoked).isTrue()
    } else {
      verifyZeroInteractions(syncScheduler)
    }
  }

  @Suppress("Unused")
  private fun `parameters for triggering sync on login`(): List<List<Any>> {
    return listOf(
        listOf(Single.just(LoginResponse("accessToken", loggedInUserPayload)), true),
        listOf(Single.error<LoginResponse>(NullPointerException()), false),
        listOf(Single.error<LoginResponse>(unauthorizedHttpError<LoginResponse>()), false),
        listOf(Single.error<LoginResponse>(IOException()), false)
    )
  }

  @Test
  @Parameters(value = [
    "true",
    "false"
  ])
  fun `error in sync should not affect login result`(syncWillFail: Boolean) {
    whenever(loginApi.login(any())).thenReturn(Single.just(LoginResponse("accessToken", PatientMocker.loggedInUserPayload())))
    whenever(syncScheduler.syncImmediately()).thenAnswer {
      if (syncWillFail) Completable.error(RuntimeException()) else Completable.complete()
    }
    whenever(ongoingLoginEntryRepository.clearLoginEntry()).thenReturn(Completable.complete())

    assertThat(userSession.loginWithOtp("000000").blockingGet()).isEqualTo(LoginResult.Success)
  }

  @Test
  @Parameters(method = "params for mapping network response for find user")
  fun `when find existing user then the network response should correctly be mapped to results`(
      response: Single<LoggedInUserPayload>,
      expectedResultType: Class<FindUserResult>
  ) {
    whenever(registrationApi.findUser(any())).thenReturn(response)

    val result = userSession.findExistingUser("1234567890").blockingGet()
    assertThat(result).isInstanceOf(expectedResultType)
  }

  @Suppress("Unused")
  private fun `params for mapping network response for find user`(): List<List<Any>> {
    val notFoundHttpError = mock<HttpException>()
    whenever(notFoundHttpError.code()).thenReturn(404)

    return listOf(
        listOf(Single.just(PatientMocker.loggedInUserPayload()), FindUserResult.Found::class.java),
        listOf(Single.error<LoggedInUserPayload>(IOException()), FindUserResult.NetworkError::class.java),
        listOf(Single.error<LoggedInUserPayload>(NullPointerException()), FindUserResult.UnexpectedError::class.java),
        listOf(Single.error<LoggedInUserPayload>(notFoundHttpError), FindUserResult.NotFound::class.java)
    )
  }

  @Test
  fun `when the server sends a user without facilities during registration then registration should be canceled`() {
    whenever(appDatabase.userDao().user()).thenReturn(Flowable.just(listOf(PatientMocker.loggedInUser())))

    val userFacility = PatientMocker.facility()
    whenever(facilityRepository.facilityUuidsForUser(any())).thenReturn(Observable.just(listOf(userFacility.uuid)))

    val response = RegistrationResponse(
        userPayload = PatientMocker.loggedInUserPayload(facilityUuids = emptyList()),
        accessToken = "token")
    whenever(registrationApi.createUser(any())).thenReturn(Single.just(response))

    val registrationResult = userSession.register().blockingGet()
    assertThat(registrationResult).isInstanceOf(RegistrationResult.Error::class.java)
  }

  @Test
  @Parameters(value = [
    "NOT_LOGGED_IN|WAITING_FOR_APPROVAL|NOT_LOGGED_IN",
    "OTP_REQUESTED|WAITING_FOR_APPROVAL|OTP_REQUESTED",
    "LOGGED_IN|WAITING_FOR_APPROVAL|LOGGED_IN",
    "RESETTING_PIN|WAITING_FOR_APPROVAL|RESETTING_PIN",
    "RESET_PIN_REQUESTED|WAITING_FOR_APPROVAL|RESET_PIN_REQUESTED",

    "NOT_LOGGED_IN|APPROVED_FOR_SYNCING|LOGGED_IN",
    "OTP_REQUESTED|APPROVED_FOR_SYNCING|LOGGED_IN",
    "LOGGED_IN|APPROVED_FOR_SYNCING|LOGGED_IN",
    "RESETTING_PIN|APPROVED_FOR_SYNCING|LOGGED_IN",
    "RESET_PIN_REQUESTED|APPROVED_FOR_SYNCING|LOGGED_IN",

    "NOT_LOGGED_IN|DISAPPROVED_FOR_SYNCING|NOT_LOGGED_IN",
    "OTP_REQUESTED|DISAPPROVED_FOR_SYNCING|OTP_REQUESTED",
    "LOGGED_IN|DISAPPROVED_FOR_SYNCING|LOGGED_IN",
    "RESETTING_PIN|DISAPPROVED_FOR_SYNCING|RESETTING_PIN",
    "RESET_PIN_REQUESTED|DISAPPROVED_FOR_SYNCING|RESET_PIN_REQUESTED"
  ]
  )
  fun `when refreshing the logged in user then the user details should be fetched from the server and login status must be updated`(
      olderLoggedInStatus: User.LoggedInStatus,
      userStatus: UserStatus,
      newerLoggedInStatus: User.LoggedInStatus
  ) {
    val loggedInUser = PatientMocker.loggedInUser(uuid = loggedInUserPayload.uuid, loggedInStatus = olderLoggedInStatus)
    val refreshedUserPayload = loggedInUserPayload.copy(status = userStatus)

    whenever(registrationApi.findUser(loggedInUserPayload.phoneNumber)).thenReturn(Single.just(refreshedUserPayload))
    whenever(facilityRepository.associateUserWithFacilities(any(), any(), any())).thenReturn(Completable.complete())
    whenever(userDao.user()).thenReturn(Flowable.just(listOf(loggedInUser)))

    userSession.refreshLoggedInUser().blockingAwait()

    verify(registrationApi).findUser(loggedInUserPayload.phoneNumber)
    verify(userDao).createOrUpdate(check {
      assertThat(it.uuid).isEqualTo(loggedInUserPayload.uuid)
      assertThat(it.status).isEqualTo(userStatus)
      assertThat(it.loggedInStatus).isEqualTo(newerLoggedInStatus)
    })
  }

  @Test
  fun `when ongoing registration entry is cleared then isOngoingRegistrationEntryPresent() should emit false`() {
    userSession.saveOngoingRegistrationEntry(OngoingRegistrationEntry())
        .andThen(userSession.clearOngoingRegistrationEntry())
        .andThen(userSession.isOngoingRegistrationEntryPresent())
        .test()
        .await()
        .assertValue(false)
  }

  @Test
  fun `when saving the user locally and the facility sync fails, the network error should correctly map results`() {
    whenever(facilitySync.pullWithResult())
        .thenReturn(
            Single.just(FacilityPullResult.Success()),
            Single.just(FacilityPullResult.UnexpectedError()),
            Single.just(FacilityPullResult.NetworkError())
        )

    var result = userSession.syncFacilityAndSaveUser(loggedInUserPayload).blockingGet()
    assertThat(result).isInstanceOf(SaveUserLocallyResult.Success::class.java)

    result = userSession.syncFacilityAndSaveUser(loggedInUserPayload).blockingGet()
    assertThat(result).isInstanceOf(SaveUserLocallyResult.UnexpectedError::class.java)

    result = userSession.syncFacilityAndSaveUser(loggedInUserPayload).blockingGet()
    assertThat(result).isInstanceOf(SaveUserLocallyResult.NetworkError::class.java)
  }

  @Test
  fun `when requesting login otp fails, the local logged in status must not be updated`() {
    whenever(loginOtpSmsListener.listenForLoginOtp()).thenReturn(Completable.complete())
    whenever(loginApi.requestLoginOtp(any()))
        .thenReturn(
            Completable.error(RuntimeException()),
            Completable.error(IOException())
        )

    userSession.requestLoginOtp().blockingGet()
    verify(userDao, never()).updateLoggedInStatusForUser(any(), any())

    userSession.requestLoginOtp().blockingGet()
    verify(userDao, never()).updateLoggedInStatusForUser(any(), any())
  }

  @Test
  fun `when requesting for login otp, the sms received event must be listened to`() {
    var listenedForSms = false

    whenever(loginOtpSmsListener.listenForLoginOtp())
        .thenReturn(Completable.complete().doOnComplete { listenedForSms = true })

    whenever(loginApi.requestLoginOtp(any()))
        .thenReturn(Completable.complete())

    userSession.requestLoginOtp().blockingGet()

    assertThat(listenedForSms).isTrue()
  }

  @Test
  fun `when listening for sms fails, the request otp call must still be made`() {
    whenever(loginOtpSmsListener.listenForLoginOtp()).thenReturn(Completable.error(RuntimeException()))

    var loginCallMade = false
    whenever(loginApi.requestLoginOtp(any()))
        .thenReturn(Completable.complete().doOnComplete { loginCallMade = true })
    userSession.requestLoginOtp().blockingGet()

    assertThat(loginCallMade).isTrue()
  }

  @Test
  fun `when performing sync and clear data, the sync must be triggered`() {
    whenever(syncScheduler.syncImmediately()).thenReturn(Completable.complete())

    val user = PatientMocker.loggedInUser()
    whenever(userDao.user()).thenReturn(Flowable.just(listOf(user)))

    userSession.syncAndClearData(patientRepository).blockingAwait()

    verify(syncScheduler).syncImmediately()
  }

  @Test
  @Parameters(value = ["0", "1", "2"])
  fun `if the sync fails when resetting PIN, it should be retried and complete if any retry succeeds`(retryCount: Int) {
    // Mockito doesn't have a way to specify a vararg for all invocations and expects
    // the first emission to be explicitly provided. This dynamically constructs the
    // rest of the emissions and ensures that the last one succeeds.
    val emissionsAfterFirst: Array<Completable> = (0 until retryCount)
        .map { retryIndex ->
          if (retryIndex == retryCount - 1) Completable.complete() else Completable.error(RuntimeException())
        }.toTypedArray()

    whenever(syncScheduler.syncImmediately())
        .thenReturn(Completable.error(RuntimeException()), *emissionsAfterFirst)

    val user = PatientMocker.loggedInUser()
    whenever(userDao.user()).thenReturn(Flowable.just(listOf(user)))

    userSession.syncAndClearData(patientRepository, retryCount)
        .test()
        .await()
        .assertComplete()
  }

  @Test
  @Parameters(value = ["0", "1", "2"])
  fun `if the sync fails when resetting PIN, it should be retried and complete if all retries fail`(retryCount: Int) {
    val emissionsAfterFirst: Array<Completable> = (0 until retryCount)
        .map { Completable.error(RuntimeException()) }.toTypedArray()

    whenever(syncScheduler.syncImmediately())
        .thenReturn(Completable.error(RuntimeException()), *emissionsAfterFirst)

    val user = PatientMocker.loggedInUser()
    whenever(userDao.user()).thenReturn(Flowable.just(listOf(user)))

    userSession.syncAndClearData(patientRepository, retryCount)
        .test()
        .await()
        .assertComplete()
  }

  @Test
  fun `if the sync succeeds when resetting the PIN, it should clear the patient related data`() {
    whenever(syncScheduler.syncImmediately()).thenReturn(Completable.complete())

    val user = PatientMocker.loggedInUser()
    whenever(userDao.user()).thenReturn(Flowable.just(listOf(user)))

    userSession.syncAndClearData(patientRepository)
        .test()
        .await()

    verify(patientRepository).clearPatientData()
  }

  @Test
  fun `if the sync fails when resetting the PIN, it should clear the patient related data`() {
    whenever(syncScheduler.syncImmediately()).thenReturn(Completable.complete())

    val user = PatientMocker.loggedInUser()
    whenever(userDao.user()).thenReturn(Flowable.just(listOf(user)))

    userSession.syncAndClearData(patientRepository)
        .test()
        .await()

    verify(patientRepository).clearPatientData()
  }

  @Test
  fun `after clearing patient related data during forgot PIN flow, the user status must be set to RESETTING_PIN`() {
    whenever(syncScheduler.syncImmediately()).thenReturn(Completable.complete())

    val user = PatientMocker.loggedInUser()
    whenever(userDao.user()).thenReturn(Flowable.just(listOf(user)))

    userSession.syncAndClearData(patientRepository)
        .test()
        .await()

    verify(userDao).updateLoggedInStatusForUser(user.uuid, User.LoggedInStatus.RESETTING_PIN)
  }

  @Test
  fun `after clearing patient related data during forgot PIN flow, the sync timestamps must be cleared`() {
    whenever(syncScheduler.syncImmediately()).thenReturn(Completable.complete())

    val user = PatientMocker.loggedInUser()
    whenever(userDao.user()).thenReturn(Flowable.just(listOf(user)))

    var bruteForceReset = false
    whenever(bruteForceProtection.resetFailedAttempts()).thenReturn(Completable.fromAction { bruteForceReset = true })

    userSession.syncAndClearData(patientRepository).blockingAwait()

    verify(patientPullTimestamp).delete()
    verify(bpPullTimestamp).delete()
    verify(appointmentPullTimestamp).delete()
    verify(communicationPullTimestamp).delete()
    verify(medicalHistoryPullTimestamp).delete()
    verify(prescriptionPullTimestamp).delete()
    assertThat(bruteForceReset).isTrue()
  }

  @Test
  @Parameters(value = [
    "0000|password-1",
    "1111|password-2"
  ])
  fun `when reset PIN request is raised, the network call must be made with the hashed PIN`(
      pin: String,
      hashed: String
  ) {
    val currentUser = PatientMocker.loggedInUser(pinDigest = "old-digest", loggedInStatus = User.LoggedInStatus.RESETTING_PIN)
    whenever(userDao.user()).thenReturn(Flowable.just(listOf(currentUser)))

    whenever(passwordHasher.hash(any())).thenReturn(Single.just(hashed))
    whenever(loginApi.resetPin(any()))
        .thenReturn(Single.just(ForgotPinResponse(
            accessToken = "",
            loggedInUser = PatientMocker.loggedInUserPayload()
        )))

    userSession.resetPin(pin).blockingGet()

    verify(loginApi).resetPin(ResetPinRequest(hashed))
  }

  @Test
  fun `when the password hashing fails on resetting PIN, an expected error must be thrown`() {
    val currentUser = PatientMocker.loggedInUser(pinDigest = "old-digest", loggedInStatus = User.LoggedInStatus.RESETTING_PIN)
    whenever(userDao.user()).thenReturn(Flowable.just(listOf(currentUser)))

    val exception = RuntimeException()
    whenever(passwordHasher.hash(any())).thenReturn(Single.error(exception))

    val result = userSession.resetPin("0000").blockingGet()
    assertThat(result).isEqualTo(ForgotPinResult.UnexpectedError(exception))
  }

  @Test
  @Parameters(method = "params for forgot pin api")
  fun `the appropriate result must be returned when the reset pin call finishes`(
      apiResult: Single<ForgotPinResponse>,
      expectedResult: ForgotPinResult
  ) {
    val currentUser = PatientMocker.loggedInUser(pinDigest = "old-digest", loggedInStatus = User.LoggedInStatus.RESETTING_PIN)
    whenever(userDao.user()).thenReturn(Flowable.just(listOf(currentUser)))

    whenever(passwordHasher.hash(any())).thenReturn(Single.just("hashed"))
    whenever(loginApi.resetPin(any())).thenReturn(apiResult)

    val result = userSession.resetPin("0000").blockingGet()
    assertThat(result).isEqualTo(expectedResult)
  }

  @Suppress("Unused")
  private fun `params for forgot pin api`(): Array<Array<Any>> {
    val exception = RuntimeException()
    return arrayOf(
        arrayOf(Single.error<ForgotPinResponse>(IOException()), ForgotPinResult.NetworkError),
        arrayOf(Single.error<ForgotPinResponse>(unauthorizedHttpError<Any>()), ForgotPinResult.UserNotFound),
        arrayOf(Single.error<ForgotPinResponse>(exception), ForgotPinResult.UnexpectedError(exception))
    )
  }

  @Test
  fun `whenever the forgot pin api succeeds, the logged in users password digest and logged in status must be updated`() {
    val currentUser = PatientMocker.loggedInUser(
        pinDigest = "old-digest",
        loggedInStatus = User.LoggedInStatus.RESETTING_PIN,
        status = UserStatus.APPROVED_FOR_SYNCING
    )
    whenever(userDao.user()).thenReturn(Flowable.just(listOf(currentUser)))

    whenever(passwordHasher.hash(any())).thenReturn(Single.just("new-digest"))

    val response = ForgotPinResponse(
        loggedInUser = PatientMocker.loggedInUserPayload(
            uuid = currentUser.uuid,
            name = currentUser.fullName,
            phone = currentUser.phoneNumber,
            pinDigest = "new-digest",
            status = UserStatus.WAITING_FOR_APPROVAL,
            createdAt = currentUser.createdAt,
            updatedAt = currentUser.updatedAt
        ),
        accessToken = "new_access_token"
    )
    whenever(loginApi.resetPin(any())).thenReturn(Single.just(response))

    userSession.resetPin("0000").blockingGet()

    verify(userDao).createOrUpdate(currentUser.copy(
        pinDigest = "new-digest",
        loggedInStatus = User.LoggedInStatus.RESET_PIN_REQUESTED,
        status = UserStatus.WAITING_FOR_APPROVAL
    ))
  }

  @Test
  fun `whenever the forgot pin api succeeds, the access token must be updated`() {
    val currentUser = PatientMocker.loggedInUser(
        pinDigest = "old-digest",
        loggedInStatus = User.LoggedInStatus.RESETTING_PIN,
        status = UserStatus.APPROVED_FOR_SYNCING
    )
    whenever(userDao.user()).thenReturn(Flowable.just(listOf(currentUser)))

    whenever(passwordHasher.hash(any())).thenReturn(Single.just("new-digest"))

    val response = ForgotPinResponse(
        loggedInUser = PatientMocker.loggedInUserPayload(
            uuid = currentUser.uuid,
            name = currentUser.fullName,
            phone = currentUser.phoneNumber,
            pinDigest = "new-digest",
            status = UserStatus.WAITING_FOR_APPROVAL,
            createdAt = currentUser.createdAt,
            updatedAt = currentUser.updatedAt
        ),
        accessToken = "new_access_token"
    )
    whenever(loginApi.resetPin(any())).thenReturn(Single.just(response))

    userSession.resetPin("0000").blockingGet()

    verify(accessTokenPref).set(Just("new_access_token"))
  }

  @Test
  fun `whenever the forgot pin api call fails, the logged in user must not be updated`() {
    val currentUser = PatientMocker.loggedInUser(pinDigest = "old-digest", loggedInStatus = User.LoggedInStatus.RESETTING_PIN)
    whenever(userDao.user()).thenReturn(Flowable.just(listOf(currentUser)))

    whenever(passwordHasher.hash(any())).thenReturn(Single.just("hashed"))
    whenever(loginApi.resetPin(any())).thenReturn(Single.error<ForgotPinResponse>(RuntimeException()))

    userSession.resetPin("0000").blockingGet()

    verify(userDao, never()).createOrUpdate(any())
  }

  @Test
  fun `whenever the forgot pin api call fails, the access token must not be updated`() {
    val currentUser = PatientMocker.loggedInUser(pinDigest = "old-digest", loggedInStatus = User.LoggedInStatus.RESETTING_PIN)
    whenever(userDao.user()).thenReturn(Flowable.just(listOf(currentUser)))

    whenever(passwordHasher.hash(any())).thenReturn(Single.just("hashed"))
    whenever(loginApi.resetPin(any())).thenReturn(Single.error<ForgotPinResponse>(RuntimeException()))

    userSession.resetPin("0000").blockingGet()

    verify(accessTokenPref, never()).set(any())
  }

  @Test
  @Parameters(method = "params for saving user after reset pin")
  fun `the appropriate response must be returned when saving the user after reset PIN call succeeds`(
      errorToThrow: Throwable?,
      expectedResult: ForgotPinResult
  ) {
    val currentUser = PatientMocker.loggedInUser(pinDigest = "old-digest", loggedInStatus = User.LoggedInStatus.RESETTING_PIN)
    whenever(userDao.user()).thenReturn(Flowable.just(listOf(currentUser)))

    errorToThrow?.let { whenever(userDao.createOrUpdate(any())).doThrow(it) }

    whenever(passwordHasher.hash(any())).thenReturn(Single.just("new-digest"))

    val response = ForgotPinResponse(
        loggedInUser = PatientMocker.loggedInUserPayload(
            uuid = currentUser.uuid,
            name = currentUser.fullName,
            phone = currentUser.phoneNumber,
            pinDigest = "new-digest",
            status = UserStatus.WAITING_FOR_APPROVAL,
            createdAt = currentUser.createdAt,
            updatedAt = currentUser.updatedAt
        ),
        accessToken = "new_access_token"
    )
    whenever(loginApi.resetPin(any())).thenReturn(Single.just(response))

    val result = userSession.resetPin("0000").blockingGet()

    assertThat(result).isEqualTo(expectedResult)
  }

  @Suppress("Unused")
  private fun `params for saving user after reset pin`(): Array<Array<Any?>> {
    val exception = java.lang.RuntimeException()
    return arrayOf(
        arrayOf<Any?>(null, ForgotPinResult.Success),
        arrayOf<Any?>(exception, ForgotPinResult.UnexpectedError(exception))
    )
  }

  @Test
  @Parameters(method = "parameters for clearing login entry")
  fun `ongoing login entry should be cleared only on successful login`(
      response: Single<LoginResponse>,
      shouldClearLoginEntry: Boolean
  ) {
    var entryCleared = false

    whenever(loginApi.login(any())).thenReturn(response)
    whenever(syncScheduler.syncImmediately()).thenReturn(Completable.complete())
    whenever(ongoingLoginEntryRepository.clearLoginEntry()).thenReturn(Completable.complete().doOnComplete { entryCleared = true })

    userSession.loginWithOtp("000000").blockingGet()

    if (shouldClearLoginEntry) {
      assertThat(entryCleared).isTrue()
    } else {
      assertThat(entryCleared).isFalse()
    }
  }

  @Suppress("Unused")
  private fun `parameters for clearing login entry`(): List<List<Any>> {
    return listOf(
        listOf(Single.just(LoginResponse("accessToken", loggedInUserPayload)), true),
        listOf(Single.error<LoginResponse>(NullPointerException()), false),
        listOf(Single.error<LoginResponse>(unauthorizedHttpError<LoginResponse>()), false),
        listOf(Single.error<LoginResponse>(IOException()), false)
    )
  }

  @After
  fun tearDown() {
    reporter.clear()
    Analytics.clearReporters()
  }
}
