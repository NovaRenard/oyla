package kz.oyla.server.cli

import kotlin.system.exitProcess
import kz.oyla.server.config.DatabaseFactory
import kz.oyla.server.repository.DatabaseSaasRepository
import kz.oyla.server.service.ApiException
import kz.oyla.server.service.SaasConfig
import kz.oyla.server.service.SaasService

/**
 * Deliberately small dependency-free admin entry point. It is available through the normal
 * server distribution so the production image and its Flyway/database configuration stay shared.
 */
object AdminCli {
    fun run(args: Array<String>) {
        val exitCode = try {
            when (args.firstOrNull()) {
                "create-center" -> createCenter(args.drop(1))
                "reset-password" -> resetPassword(args.drop(1))
                "help", "--help", "-h", null -> { printUsage(); 0 }
                else -> throw CliFailure("Unknown command. Use 'create-center' or 'reset-password'.")
            }
        } catch (error: CliFailure) {
            System.err.println(error.message)
            2
        } catch (error: ApiException) {
            // ApiException messages are already curated for clients and contain no persistence details.
            System.err.println(if (error.errorCode == "CONFLICT") "Cannot complete the command because the email is already in use." else error.clientMessage)
            1
        } catch (_: Exception) {
            // Do not accidentally print JDBC URLs, secrets, hashes or stack traces to the shell.
            System.err.println("The administrative command could not be completed.")
            1
        }
        if (exitCode != 0) exitProcess(exitCode)
    }

    private fun createCenter(rawArgs: List<String>): Int {
        val arguments = Arguments.parse(rawArgs)
        val password = readPassword(arguments)
        val service = productionService()
        val result = runBlockingBridge {
            service.createCenterByAdmin(
                centerName = arguments.required("center-name"),
                firstName = arguments.required("owner-first-name"),
                lastName = arguments.optional("owner-last-name"),
                email = arguments.required("owner-email"),
                password = password,
                timezone = arguments.required("timezone")
            )
        }
        println("Center created successfully.")
        println("centerId: ${result.centerId}")
        println("centerName: ${result.centerName}")
        println("ownerEmail: ${result.ownerEmail}")
        return 0
    }

    private fun resetPassword(rawArgs: List<String>): Int {
        val arguments = Arguments.parse(rawArgs)
        val password = readPassword(arguments)
        val changed = runBlockingBridge {
            productionService().resetPasswordByAdmin(arguments.required("email"), password)
        }
        if (!changed) throw CliFailure("Password reset could not be completed.")
        println("Password reset successfully. Existing web sessions have been revoked.")
        return 0
    }

    private fun productionService(): SaasService {
        DatabaseFactory.initialize() // runs Flyway before any admin mutation
        return SaasService(DatabaseSaasRepository(), SaasConfig.fromEnvironment())
    }

    private fun readPassword(arguments: Arguments): String {
        System.getenv("OYLA_ADMIN_INITIAL_PASSWORD")?.takeIf { it.isNotEmpty() }?.let { return it }
        if (arguments.passwordFromStdin) {
            return generateSequence { System.`in`.read().takeIf { it >= 0 }?.toChar() }
                .joinToString("").trimEnd('\r', '\n').takeIf { it.isNotEmpty() }
                ?: throw CliFailure("Password was not provided on stdin.")
        }
        val console = System.console() ?: throw CliFailure("Set OYLA_ADMIN_INITIAL_PASSWORD or use --password-stdin in non-interactive mode.")
        return console.readPassword("Initial password: ")?.concatToString()?.takeIf { it.isNotEmpty() }
            ?: throw CliFailure("Password was not provided.")
    }

    private fun printUsage() = println(
        """Usage:
  oyla-admin create-center --center-name <name> --owner-first-name <name> --owner-email <email> --timezone <IANA timezone> [--owner-last-name <name>] [--password-stdin]
  oyla-admin reset-password --email <email> [--password-stdin]

Passwords are read interactively, from OYLA_ADMIN_INITIAL_PASSWORD, or from stdin with --password-stdin. Never pass a password as a command-line argument."""
    )

    private class Arguments private constructor(private val values: Map<String, String>, val passwordFromStdin: Boolean) {
        fun required(name: String): String = values[name] ?: throw CliFailure("Missing --$name.")
        fun optional(name: String): String? = values[name]
        companion object {
            fun parse(raw: List<String>): Arguments {
                val values = linkedMapOf<String, String>()
                var passwordFromStdin = false
                var index = 0
                while (index < raw.size) {
                    val key = raw[index]
                    if (key == "--password" || key.startsWith("--password=")) throw CliFailure("Passwords cannot be passed as command-line arguments.")
                    if (key == "--password-stdin") { passwordFromStdin = true; index++; continue }
                    if (!key.startsWith("--")) throw CliFailure("Unexpected argument: $key")
                    val name = key.removePrefix("--")
                    val value = raw.getOrNull(index + 1)?.takeUnless { it.startsWith("--") }
                        ?: throw CliFailure("Missing value for $key.")
                    if (values.put(name, value) != null) throw CliFailure("--$name was provided more than once.")
                    index += 2
                }
                return Arguments(values, passwordFromStdin)
            }
        }
    }

    private class CliFailure(message: String) : RuntimeException(message)

    private fun <T> runBlockingBridge(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
}
