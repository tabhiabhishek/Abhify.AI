package ai.abhify.android.update

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signature verification (RFC 8032, section 6 reference algorithm), pure Kotlin.
 * A port of backend/ed25519_verify.py so the phone verifies the same "abhify-update-1" signatures as
 * the Windows app. Built-in Ed25519 only exists on Android 13+, and this app supports Android 10+.
 * Verification of public data only: not constant-time, and there is deliberately no signing here.
 */
object Ed25519 {
    private val TWO: BigInteger = BigInteger.valueOf(2)   // BigInteger.TWO only exists on Android 13+
    private val P: BigInteger = TWO.pow(255) - BigInteger.valueOf(19)
    private val Q: BigInteger = TWO.pow(252) + BigInteger("27742317777372353535851937790883648493")
    private val D: BigInteger = BigInteger.valueOf(-121665) * BigInteger.valueOf(121666).modPow(P - TWO, P) % P
    private val SQRT_M1: BigInteger = TWO.modPow((P - BigInteger.ONE) / BigInteger.valueOf(4), P)

    private data class Pt(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private fun BigInteger.m() = this.mod(P)

    private fun add(a: Pt, b: Pt): Pt {
        val aa = ((a.y - a.x) * (b.y - b.x)).m()
        val bb = ((a.y + a.x) * (b.y + b.x)).m()
        val c = (TWO * a.t * b.t * D).m()
        val d = (TWO * a.z * b.z).m()
        val e = bb - aa; val f = d - c; val g = d + c; val h = bb + aa
        return Pt((e * f).m(), (g * h).m(), (f * g).m(), (e * h).m())
    }

    private fun mul(s0: BigInteger, p0: Pt): Pt {
        var s = s0; var p = p0
        var q = Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        while (s.signum() > 0) {
            if (s.testBit(0)) q = add(q, p)
            p = add(p, p)
            s = s.shiftRight(1)
        }
        return q
    }

    private fun equal(a: Pt, b: Pt) =
        (a.x * b.z - b.x * a.z).m().signum() == 0 && (a.y * b.z - b.y * a.z).m().signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val x2 = ((y * y - BigInteger.ONE) * (D * y * y + BigInteger.ONE).modPow(P - TWO, P)).m()
        if (x2.signum() == 0) return if (sign != 0) null else BigInteger.ZERO
        var x = x2.modPow((P + BigInteger.valueOf(3)) / BigInteger.valueOf(8), P)
        if ((x * x - x2).m().signum() != 0) x = (x * SQRT_M1).m()
        if ((x * x - x2).m().signum() != 0) return null
        if (x.testBit(0) != (sign == 1)) x = P - x
        return x
    }

    private val G: Pt = run {
        val gy = (BigInteger.valueOf(4) * BigInteger.valueOf(5).modPow(P - TWO, P)).m()
        val gx = recoverX(gy, 0)!!
        Pt(gx, gy, BigInteger.ONE, (gx * gy).m())
    }

    private fun le(bytes: ByteArray) = BigInteger(1, bytes.reversedArray())

    private fun decompress(s: ByteArray): Pt? {
        if (s.size != 32) return null
        var y = le(s)
        val sign = if (y.testBit(255)) 1 else 0
        y = y.clearBit(255)
        val x = recoverX(y, sign) ?: return null
        return Pt(x, y, BigInteger.ONE, (x * y).m())
    }

    private fun sha512ModQ(vararg parts: ByteArray): BigInteger {
        val md = MessageDigest.getInstance("SHA-512")
        parts.forEach { md.update(it) }
        return le(md.digest()).mod(Q)
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decompress(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, 32)
        val r = decompress(rBytes) ?: return false
        val s = le(signature.copyOfRange(32, 64))
        if (s >= Q) return false
        val h = sha512ModQ(rBytes, publicKey, message)
        return equal(mul(s, G), add(r, mul(h, a)))
    }
}
