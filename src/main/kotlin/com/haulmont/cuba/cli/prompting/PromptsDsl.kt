/*
 * Copyright (c) 2008-2018 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.haulmont.cuba.cli.prompting

sealed class Question(val name: String)

abstract class SimpleQuestion<T : Any>(name: String, val caption: String) : Question(name), Print<T>, Read<T>, WithValidation<T>, HasDefault<T> {
    open fun printPrompts(answers: Answers): String =
            """> $caption ${printDefaultValue(answers)}"""


    open fun printDefaultValue(answers: Answers): String {
        val defaultValue = this.defaultValue
        return when (defaultValue) {
            None -> ""
            is PlainValue -> defaultValue.value.print()
            is CalculatedValue -> defaultValue.function(answers).print()
        }.let {
            if (it.isEmpty()) it
            else "@|red ($it) |@"
        }
    }
}

abstract class CompositeQuestion(name: String) : Iterable<Question>, Question(name) {
    protected val questions: MutableList<Question> = mutableListOf()

    fun question(name: String, caption: String, configuration: (PlainQuestionConfigurationScope.() -> Unit)? = null) {
        PlainQuestion(name, caption).apply {
            configuration?.let { this.it() }
            questions.add(this)
        }
    }

    fun options(name: String, caption: String, options: List<String>, configuration: (DefaultValueConfigurable<Int>.() -> Unit)? = null) {
        OptionsQuestion(name, caption, options).apply {
            configuration?.let { this.it() }
            questions.add(this)
        }
    }

    fun confirmation(name: String, caption: String, configuration: (ConfirmationQuestionConfigurationScope.() -> Unit)? = null) {
        ConfirmationQuestion(name, caption).apply {
            configuration?.let { this.it() }
            questions.add(this)
        }
    }

    override fun iterator(): Iterator<Question> = questions.iterator()
}

class QuestionsList(name: String = "", setup: (QuestionsList.() -> Unit)) : CompositeQuestion(name) {
    init {
        setup()

        check(questions.isNotEmpty())

        questions.groupingBy { it.name }
                .eachCount()
                .entries
                .firstOrNull { (_, count) -> count > 1 }
                ?.let { (name, _) -> throw RuntimeException("Duplicated questions with name $name") }
    }
}

class PlainQuestion(name: String, caption: String) :
        SimpleQuestion<String>(name, caption),
        HasDefault<String>,
        WithValidation<String>,
        PlainQuestionConfigurationScope {

    override var validation: (String) -> Unit = acceptAll

    override var defaultValue: DefaultValue<String> = None

    override fun String.read(): String = this

    override fun String.print() = this
}

interface PlainQuestionConfigurationScope : DefaultValueConfigurable<String>, ValidationConfigurable<String>

class OptionsQuestion(name: String, caption: String, val options: List<String>) :
        SimpleQuestion<Int>(name, caption),
        HasDefault<Int>,
        WithValidation<Int> {

    override var defaultValue: DefaultValue<Int> = None

    override var validation: (Int) -> Unit = {
        ValidationHelper(it).run {
            try {
                if (it in (0 until options.size))
                    return@run
            } catch (e: NumberFormatException) {
            }

            fail("Input 1-${options.size}")
        }
    }

    init {
        check(options.isNotEmpty())
    }

    override fun String.read(): Int {
        try {
            return this.toInt() - 1
        } catch (e: Exception) {
            throw ReadException("Input 1-${options.size}")
        }
    }

    override fun Int.print(): String = (this + 1).toString()

    override fun printPrompts(answers: Answers): String {
        return buildString {
            append(super.printPrompts(answers))
            options.forEachIndexed { index, option ->
                append("\n${index + 1}. $option ")
            }
        }
    }
}

interface ConfirmationQuestionConfigurationScope : DefaultValueConfigurable<Boolean>

class ConfirmationQuestion(name: String, caption: String) :
        SimpleQuestion<Boolean>(name, caption),
        ConfirmationQuestionConfigurationScope {

    override var defaultValue: DefaultValue<Boolean> = None
    override var validation: (Boolean) -> Unit = acceptAll

    override fun String.read(): Boolean {
        val asChars = this.toLowerCase().trim().toCharArray()
        if (asChars.size != 1) throw ReadException()
        return when (asChars[0]) {
            'y' -> true
            'n' -> false
            else -> throw ReadException()
        }
    }

    override fun printDefaultValue(answers: Answers): String {
        val defaultValue = this.defaultValue
        return when (defaultValue) {
            None -> " (y/n) "
            is PlainValue -> if (defaultValue.value) " (Y/n) " else " (y/N) "
            is CalculatedValue -> if (defaultValue.function(answers)) " (Y/n) " else " (y/N) "
        }
    }

    override fun Boolean.print(): String = if (this) "y" else "n"
}

sealed class DefaultValue<out T>
object None : DefaultValue<Nothing>()
class PlainValue<out T>(val value: T) : DefaultValue<T>()
class CalculatedValue<out T>(val function: (Answers) -> T) : DefaultValue<T>()

typealias Answer = Any
typealias Answers = Map<String, Answer>

interface DefaultValueConfigurable<T> {
    fun default(function: (answers: Answers) -> T)

    fun default(plain: T)
}

interface HasDefault<T : Any> : DefaultValueConfigurable<T> {
    var defaultValue: DefaultValue<T>

    override fun default(function: (answers: Answers) -> T) {
        defaultValue = CalculatedValue { function(it) }
    }

    override fun default(plain: T) {
        defaultValue = PlainValue(plain)
    }
}

private val acceptAll: (Any) -> Unit = {}

interface ValidationConfigurable<T : Any> {
    fun validate(block: ValidationHelper<T>.(T) -> Unit)
}

interface WithValidation<T : Any> : ValidationConfigurable<T> {
    var validation: (T) -> Unit

    override fun validate(block: ValidationHelper<T>.(T) -> Unit) {
        check(validation == acceptAll)
        validation = { ValidationHelper(it).block(it) }
    }
}

class ValidationHelper<T : Any>(private val value: T) {
    fun checkRegex(pattern: String, failMessage: String = "Invalid value") {
        if (value !is String) {
            throw RuntimeException("Trying to validate non string value with regex")
        }
        if (!Regex(pattern).matches(value))
            fail(failMessage)
    }

    fun checkIsPackage(failMessage: String = "Is not valid package name") =
            checkRegex("[a-zA-Z][0-9a-zA-Z]*(\\.[a-zA-Z][0-9a-zA-Z]*)*", failMessage)

    fun checkIsClass(failMessage: String = "Invalid class name") =
            checkRegex("\\b[A-Z]+[\\w\\d]*", failMessage)

    fun fail(cause: String): Nothing = throw ValidationException(cause)
}

interface Print<in T : Any> {
    fun T.print() = this.toString()
}

interface Read<out T : Any> {
    fun String.read(): T
}

class ReadException(message: String = "Invalid value") : Exception(message)