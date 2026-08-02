package kz.oyla.app.data.remote

sealed interface NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>
    data class HttpError(val statusCode: Int, val errorCode: String? = null) : NetworkResult<Nothing>
    data object NetworkError : NetworkResult<Nothing>
}
