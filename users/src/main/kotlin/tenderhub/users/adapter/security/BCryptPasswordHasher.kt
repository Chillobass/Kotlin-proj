package tenderhub.users.adapter.security

import org.springframework.security.crypto.password.PasswordEncoder
import tenderhub.users.application.PasswordHasher

class BCryptPasswordHasher(private val encoder: PasswordEncoder) : PasswordHasher {
    override fun hash(raw: String): String = encoder.encode(raw)

    override fun matches(raw: String, hash: String): Boolean = encoder.matches(raw, hash)
}
