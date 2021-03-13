package com.theapache64.stackzy.data.repo

import com.github.theapache64.gpa.api.Play
import com.github.theapache64.gpa.model.Account
import com.squareup.moshi.Moshi
import com.theapache64.stackzy.util.calladapter.flow.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.prefs.Preferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepo @Inject constructor(
    private val moshi: Moshi
) {

    companion object {
        private const val KEY_ACCOUNT = "account"
    }

    private val accountAdapter by lazy {
        moshi.adapter(Account::class.java)
    }

    private val pref by lazy {
        Preferences.userRoot().node(AuthRepo::class.java.simpleName)
    }

    /**
     * To get active account
     */
    fun getAccount(): Account? {
        val accountJson = pref.get(KEY_ACCOUNT, null)
        return if (accountJson != null) {
            // Parse
            accountAdapter.fromJson(accountJson)
        } else {
            null
        }
    }

    /**
     * To login with given google username and password
     */
    suspend fun logIn(username: String, password: String) = withContext(Dispatchers.IO) {
        flow<Resource<Account>> {
            // Loading
            emit(Resource.Loading())

            try {
                val account = Play.login(username, password)
                emit(Resource.Success(null, account))
            } catch (e: Exception) {
                e.printStackTrace()
                emit(Resource.Error(e.message ?: "Something went wrong"))
            }
        }
    }

    /**
     * To persist account
     */
    fun storeAccount(account: Account) {
        val accountJson = accountAdapter.toJson(account)
        pref.put(KEY_ACCOUNT, accountJson)
    }

    /**
     * To logout and remove account details from preference
     */
    fun logout() {
        pref.remove(KEY_ACCOUNT)
    }


}