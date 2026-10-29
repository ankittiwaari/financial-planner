package com.algopraxis.dhanniti.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.algopraxis.dhanniti.data.api.ApiException
import com.algopraxis.dhanniti.data.auth.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = email.isNotBlank() && password.isNotEmpty() && !isSubmitting
}

class LoginViewModel(private val authRepository: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            try {
                // On success the token is saved and App() switches to the signed-in screens.
                authRepository.login(current.email, current.password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.Validation) {
                _state.update { it.copy(isSubmitting = false, error = e.errors.values.flatten().firstOrNull() ?: e.message) }
            } catch (e: ApiException) {
                _state.update { it.copy(isSubmitting = false, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(isSubmitting = false, error = "Can't reach the server. Check your connection and try again.") }
            }
        }
    }
}
