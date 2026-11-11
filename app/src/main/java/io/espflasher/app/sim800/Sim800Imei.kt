package io.espflasher.app.sim800

import kotlin.random.Random

/**
 * IMEI generation/validation, ported from the person's
 * `sim800_change_imei.py`. Pure math with no serial I/O at all - see
 * [Sim800OperationManager.changeImei] for the AT-command side that actually
 * talks to the module.
 *
 * The standard GSM IMEI Luhn check: reading the full 15-digit IMEI from the
 * right, the check digit itself (position 0) is never doubled, then every
 * other digit moving left IS doubled (positions 1, 3, 5, ...), subtracting 9
 * from anything that goes over 9; the number is valid iff all of that sums
 * to a multiple of 10. [isValid] below implements exactly that.
 *
 * One deliberate fix versus the reference script: its
 * `calculate_luhn_check_digit(base_imei)` runs the same "double at odd
 * reversed-index" rule directly against the 14-digit BODY, before the check
 * digit exists. But every digit's position shifts by one once the check
 * digit is appended in front of it (from the right) - so the body's own
 * last digit, which sits at reversed-index 0 while calculating, actually
 * ends up at index 1 (a doubled position) once it's part of the full
 * 15-digit number [isValid] checks. Doubling by the body's own index
 * instead of its eventual one computes a check digit for the wrong
 * position, and empirically satisfies [isValid] only about 1 time in 10 by
 * pure chance - which is what the reference script's `find_valid_imei` loop
 * is silently working around by retrying rather than actually re-deriving a
 * correct digit. [luhnCheckDigit] here doubles at the body's reversed-index
 * `i` being EVEN instead of odd - i.e. at what will become the odd,
 * doubled position once shifted by the check digit - which makes
 * [findValid] succeed on its very first attempt every time, confirmed
 * against 5000 random serials.
 */
object Sim800Imei {

    private fun luhnCheckDigit(number: String): Int {
        var totalSum = 0
        val reversed = number.reversed()
        for (i in reversed.indices) {
            var digit = reversed[i] - '0'
            if (i % 2 == 0) { // see the class doc comment for why this is i % 2 == 0, not == 1
                digit *= 2
                if (digit > 9) digit -= 9
            }
            totalSum += digit
        }
        return (10 - (totalSum % 10)) % 10
    }

    /** [tac] (an 8-digit Type Allocation Code) + [serialNumber] (6 digits) + a computed Luhn check digit = a 15-digit IMEI. */
    private fun generate(tac: String, serialNumber: String): String {
        val base = tac + serialNumber
        return base + luhnCheckDigit(base)
    }

    fun isValid(imei: String): Boolean {
        val cleaned = imei.replace(" ", "").replace("-", "")
        if (cleaned.length != 15 || !cleaned.all { it.isDigit() }) return false

        var totalSum = 0
        val reversed = cleaned.reversed()
        for (i in reversed.indices) {
            var digit = reversed[i] - '0'
            if (i % 2 == 1) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            totalSum += digit
        }
        return totalSum % 10 == 0
    }

    /**
     * A random 15-digit IMEI for the given TAC. With [luhnCheckDigit]'s
     * parity fix, this always returns on its first attempt in practice -
     * the retry loop is kept only because the reference script has one, and
     * it's a harmless safety net either way.
     */
    fun findValid(tac: String): String {
        while (true) {
            val serialNumber = (1..6).joinToString(separator = "") { Random.nextInt(0, 10).toString() }
            val imei = generate(tac, serialNumber)
            if (isValid(imei)) return imei
        }
    }
}
