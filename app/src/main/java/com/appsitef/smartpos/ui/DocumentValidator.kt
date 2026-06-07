package com.appsitef.smartpos.ui

object DocumentValidator {

    fun isValidCpfOrCnpj(raw: String): Boolean {
        val digits = raw.filter { it.isDigit() }
        return when (digits.length) {
            11 -> isValidCpf(digits)
            14 -> isValidCnpj(digits)
            else -> false
        }
    }

    fun isValidCpf(cpf: String): Boolean {
        val digits = cpf.filter { it.isDigit() }
        if (digits.length != 11 || digits.all { it == digits[0] }) return false

        val numbers = digits.map { it.digitToInt() }
        val firstCheck = calculateCheckDigit(numbers.take(9), weights = intArrayOf(10, 9, 8, 7, 6, 5, 4, 3, 2))
        if (firstCheck != numbers[9]) return false

        val secondCheck = calculateCheckDigit(numbers.take(10), weights = intArrayOf(11, 10, 9, 8, 7, 6, 5, 4, 3, 2))
        return secondCheck == numbers[10]
    }

    fun isValidCnpj(cnpj: String): Boolean {
        val digits = cnpj.filter { it.isDigit() }
        if (digits.length != 14 || digits.all { it == digits[0] }) return false

        val numbers = digits.map { it.digitToInt() }
        val firstWeights = intArrayOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
        val firstCheck = calculateCheckDigit(numbers.take(12), weights = firstWeights)
        if (firstCheck != numbers[12]) return false

        val secondWeights = intArrayOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
        val secondCheck = calculateCheckDigit(numbers.take(13), weights = secondWeights)
        return secondCheck == numbers[13]
    }

    private fun calculateCheckDigit(numbers: List<Int>, weights: IntArray): Int {
        val sum = numbers.indices.sumOf { index -> numbers[index] * weights[index] }
        val remainder = sum % 11
        return if (remainder < 2) 0 else 11 - remainder
    }
}
