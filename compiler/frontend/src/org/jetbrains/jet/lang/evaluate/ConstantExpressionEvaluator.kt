/*
 * Copyright 2010-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.lang.evaluate

import com.intellij.psi.tree.IElementType
import org.jetbrains.jet.lang.descriptors.annotations.AnnotationDescriptorImpl
import org.jetbrains.jet.lang.descriptors.*
import org.jetbrains.jet.lang.psi.*
import org.jetbrains.jet.lang.resolve.*
import org.jetbrains.jet.lang.resolve.calls.model.ResolvedCall
import org.jetbrains.jet.lang.resolve.constants.*
import org.jetbrains.jet.lang.resolve.name.Name
import org.jetbrains.jet.lang.types.JetType
import org.jetbrains.jet.lang.types.expressions.OperatorConventions
import org.jetbrains.jet.lang.types.lang.KotlinBuiltIns
import org.jetbrains.jet.lexer.JetTokens
import org.jetbrains.jet.lang.resolve.BindingContext.COMPILE_TIME_INITIALIZER
import org.jetbrains.jet.lang.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.jet.lang.types.TypeUtils
import org.jetbrains.jet.lang.resolve.calls.model.ResolvedValueArgument
import org.jetbrains.jet.JetNodeTypes
import java.math.BigInteger
import org.jetbrains.jet.lang.diagnostics.Errors
import com.intellij.psi.util.PsiTreeUtil

[suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")]
public class ConstantExpressionEvaluator private (val trace: BindingTrace) : JetVisitor<CompileTimeConstant<*>, JetType>() {

    class object {
        public fun evaluate(expression: JetExpression, trace: BindingTrace, expectedType: JetType? = TypeUtils.NO_EXPECTED_TYPE): CompileTimeConstant<*>? {
            val evaluator = ConstantExpressionEvaluator(trace)
            return evaluator.evaluate(expression, expectedType)
        }
    }

    private fun evaluate(expression: JetExpression, expectedType: JetType?): CompileTimeConstant<*>? {
        val recordedCompileTimeConstant = trace.get(BindingContext.COMPILE_TIME_VALUE, expression)
        if (recordedCompileTimeConstant != null) {
            return recordedCompileTimeConstant
        }

        val compileTimeConstant = expression.accept(this, expectedType ?: TypeUtils.NO_EXPECTED_TYPE)
        if (compileTimeConstant != null) {
            trace.record(BindingContext.COMPILE_TIME_VALUE, expression, compileTimeConstant)
            return compileTimeConstant
        }

        return null
    }

    private val stringExpressionEvaluator = object : JetVisitor<StringValue, Nothing>() {
        fun evaluate(entry: JetStringTemplateEntry): StringValue? {
            return entry.accept(this, null)
        }

        override fun visitStringTemplateEntryWithExpression(entry: JetStringTemplateEntryWithExpression, data: Nothing?): StringValue? {
            val expression = entry.getExpression()
            if (expression == null) return null

            return createStringConstant(this@ConstantExpressionEvaluator.evaluate(expression, KotlinBuiltIns.getInstance().getStringType()))
        }

        override fun visitLiteralStringTemplateEntry(entry: JetLiteralStringTemplateEntry, data: Nothing?) = StringValue(entry.getText(), true)

        override fun visitEscapeStringTemplateEntry(entry: JetEscapeStringTemplateEntry, data: Nothing?) = StringValue(entry.getUnescapedValue(), true)
    }

    override fun visitConstantExpression(expression: JetConstantExpression, expectedType: JetType?): CompileTimeConstant<*>? {
        val text = expression.getText()
        if (text == null) return null
        val result: Any? = when (expression.getNode().getElementType()) {
            JetNodeTypes.INTEGER_CONSTANT -> parseLong(text)
            JetNodeTypes.FLOAT_CONSTANT -> parseFloatingLiteral(text)
            JetNodeTypes.BOOLEAN_CONSTANT -> parseBoolean(text)
            JetNodeTypes.CHARACTER_CONSTANT -> CompileTimeConstantChecker.parseChar(expression)
            JetNodeTypes.NULL -> null
            else -> throw IllegalArgumentException("Unsupported constant: " + expression)
        }
        if (result == null && expression.getNode().getElementType() == JetNodeTypes.NULL) return NullValue.NULL

        fun isLongWithSuffix() = expression.getNode().getElementType() == JetNodeTypes.INTEGER_CONSTANT && hasLongSuffix(text)

        return createCompileTimeConstant(result, expression, expectedType, !isLongWithSuffix())
    }

    override fun visitParenthesizedExpression(expression: JetParenthesizedExpression, expectedType: JetType?): CompileTimeConstant<*>? {
        val deparenthesizedExpression = JetPsiUtil.deparenthesize(expression)
        if (deparenthesizedExpression != null && deparenthesizedExpression != expression) {
            val compileTimeConstant = evaluate(deparenthesizedExpression, expectedType)
            val isDeparentesizedPure = trace.get(BindingContext.IS_PURE_CONSTANT_EXPRESSION, deparenthesizedExpression)
            if (isDeparentesizedPure != null && isDeparentesizedPure!!) {
                trace.record(BindingContext.IS_PURE_CONSTANT_EXPRESSION, expression, true)
            }
            return compileTimeConstant
        }
        return null
    }

    override fun visitPrefixExpression(expression: JetPrefixExpression, expectedType: JetType?): CompileTimeConstant<*>? {
        val deparenthesizedExpression = JetPsiUtil.deparenthesize(expression)
        return if (deparenthesizedExpression != null && deparenthesizedExpression != expression) {
            evaluate(deparenthesizedExpression, expectedType)
        }
        else {
            super.visitPrefixExpression(expression, expectedType)
        }
    }

    override fun visitStringTemplateExpression(expression: JetStringTemplateExpression, expectedType: JetType?): CompileTimeConstant<*>? {
        val sb = StringBuilder()
        var interupted = false
        var canBeUsedInAnnotation = true
        for (entry in expression.getEntries()) {
            val constant = stringExpressionEvaluator.evaluate(entry)
            if (constant == null) {
                interupted = true
                break
            }
            else {
                if (!constant.canBeUsedInAnnotations()) canBeUsedInAnnotation = false
                sb.append(constant.getValue())
            }
        }
        return if (!interupted) createCompileTimeConstant(sb.toString(), expression, expectedType, true, canBeUsedInAnnotation) else null
    }

    override fun visitBinaryExpression(expression: JetBinaryExpression, expectedType: JetType?): CompileTimeConstant<*>? {
        val leftExpression = expression.getLeft()
        if (leftExpression == null) return null

        val operationToken = expression.getOperationToken()
        if (OperatorConventions.BOOLEAN_OPERATIONS.containsKey(operationToken)) {
            val booleanType = KotlinBuiltIns.getInstance().getBooleanType()
            val leftConstant = evaluate(leftExpression, booleanType)
            if (leftConstant == null) return null

            val rightExpression = expression.getRight()
            if (rightExpression == null) return null

            val rightConstant = evaluate(rightExpression, booleanType)
            if (rightConstant == null) return null

            val leftValue = leftConstant.getValue()
            val rightValue = rightConstant.getValue()

            if (leftValue !is Boolean || rightValue !is Boolean) return null
            val result = when(operationToken) {
                JetTokens.ANDAND -> leftValue as Boolean && rightValue as Boolean
                JetTokens.OROR -> leftValue as Boolean || rightValue as Boolean
                else -> throw IllegalArgumentException("Unknown boolean operation token ${operationToken}")
            }
            return createCompileTimeConstant(result, expression, expectedType)
        }
        else {
            return evaluateCall(expression, expression.getOperationReference(), leftExpression, expectedType)
        }
    }

    private fun evaluateCall(fullExpression: JetExpression, callExpression: JetExpression, receiverExpression: JetExpression, expectedType: JetType?): CompileTimeConstant<*>? {
        val resolvedCall = trace.getBindingContext().get(BindingContext.RESOLVED_CALL, callExpression)
        if (resolvedCall == null) return null

        val resultingDescriptorName = resolvedCall.getResultingDescriptor()?.getName()
        if (resultingDescriptorName == null) return null

        val argumentForReceiver = createOperationArgumentForReceiver(resolvedCall, receiverExpression)
        if (argumentForReceiver == null) return null

        val argumentsEntrySet = resolvedCall.getValueArguments().entrySet()
        if (argumentsEntrySet.isEmpty()) {
            val result = evaluateUnaryAndCheck(argumentForReceiver, resultingDescriptorName.asString(), callExpression)
            val isArgumentPure = trace.get(BindingContext.IS_PURE_CONSTANT_EXPRESSION, argumentForReceiver.expression) ?: false
            val canBeUsedInAnnotation = canBeUsedInAnnotation(argumentForReceiver.expression)
            val isNumberConversionMethod = resultingDescriptorName in OperatorConventions.NUMBER_CONVERSIONS
            return createCompileTimeConstant(result, fullExpression, expectedType, !isNumberConversionMethod && isArgumentPure, canBeUsedInAnnotation)
        }
        else if (argumentsEntrySet.size() == 1) {
            val (parameter, argument) = argumentsEntrySet.first()

            val argumentForParameter = createOperationArgumentForFirstParameter(argument, parameter)
            if (argumentForParameter == null) return null

            if (isDivisionByZero(resultingDescriptorName.asString(), argumentForParameter.value)) {
                return ErrorValue.create("Division by zero")
            }

            val result = evaluateBinaryAndCheck(argumentForReceiver, argumentForParameter, resultingDescriptorName.asString(), callExpression)

            val canBeUsedInAnnotation = canBeUsedInAnnotation(argumentForReceiver.expression) && canBeUsedInAnnotation(argumentForParameter.expression)
            val context = EvaluatorContext(canBeUsedInAnnotation)
            return when(resultingDescriptorName) {
                OperatorConventions.COMPARE_TO -> createCompileTimeConstantForCompareTo(result, callExpression, context)
                OperatorConventions.EQUALS -> createCompileTimeConstantForEquals(result, callExpression, context)
                else -> {
                    val areArgumentsPure = trace.get(BindingContext.IS_PURE_CONSTANT_EXPRESSION, argumentForReceiver.expression) ?: false &&
                                           trace.get(BindingContext.IS_PURE_CONSTANT_EXPRESSION, argumentForParameter.expression) ?: false

                    createCompileTimeConstant(result, fullExpression, expectedType, areArgumentsPure, canBeUsedInAnnotation)
                }
            }
        }

        return null
    }

    private fun canBeUsedInAnnotation(expression: JetExpression) = trace.get(BindingContext.COMPILE_TIME_VALUE, expression)?.canBeUsedInAnnotations() ?: false

    private fun evaluateUnaryAndCheck(receiver: OperationArgument, name: String, callExpression: JetExpression): Any? {
        val functions = unaryOperations[UnaryOperationKey(receiver.ctcType, name)]
        if (functions == null) return null

        val (function, check) = functions
        val result = function(receiver.value)
        if (check == emptyUnaryFun) {
            return result
        }
        assert (isIntegerType(receiver.value), "Only integer constants should be checked for overflow")
        assert (name == "minus", "Only negation should be checked for overflow")

        if (receiver.value == result) {
            trace.report(Errors.INTEGER_OVERFLOW.on(PsiTreeUtil.getParentOfType(callExpression, javaClass<JetExpression>()) ?: callExpression))
        }
        return result
    }

    private fun evaluateBinaryAndCheck(receiver: OperationArgument, parameter: OperationArgument, name: String, callExpression: JetExpression): Any? {
        val functions = binaryOperations[BinaryOperationKey(receiver.ctcType, parameter.ctcType, name)]
        if (functions == null) return null

        val (function, checker) = functions
        val actualResult = try {
            function(receiver.value, parameter.value)
        } catch (e: Exception) {
            null
        }
        if (checker == emptyBinaryFun) {
            return actualResult
        }
        assert (isIntegerType(receiver.value) && isIntegerType(parameter.value)) { "Only integer constants should be checked for overflow" }

        fun toBigInteger(value: Any?) = BigInteger.valueOf((value as Number).toLong())

        val resultInBigIntegers = checker(toBigInteger(receiver.value), toBigInteger(parameter.value))

        if (toBigInteger(actualResult) != resultInBigIntegers) {
            trace.report(Errors.INTEGER_OVERFLOW.on(PsiTreeUtil.getParentOfType(callExpression, javaClass<JetExpression>()) ?: callExpression))
        }
        return actualResult
    }

    private fun isDivisionByZero(name: String, parameter: Any?): Boolean {
        if (name == OperatorConventions.BINARY_OPERATION_NAMES[JetTokens.DIV]!!.asString()) {
            if (isIntegerType(parameter)) {
                return (parameter as Number).toLong() == 0.toLong()
            }
            else if (parameter is Float || parameter is Double) {
                return (parameter as Number).toDouble() == 0.0
            }
        }
        return false
    }

    override fun visitUnaryExpression(expression: JetUnaryExpression, expectedType: JetType?): CompileTimeConstant<*>? {
        val leftExpression = expression.getBaseExpression()
        if (leftExpression == null) return null

        return evaluateCall(expression, expression.getOperationReference(), leftExpression, expectedType)
    }

    override fun visitSimpleNameExpression(expression: JetSimpleNameExpression, expectedType: JetType?): CompileTimeConstant<*>? {
        val enumDescriptor = trace.getBindingContext().get(BindingContext.REFERENCE_TARGET, expression);
        if (enumDescriptor != null && DescriptorUtils.isEnumEntry(enumDescriptor)) {
            return EnumValue(enumDescriptor as ClassDescriptor, true);
        }

        val resolvedCall = trace.getBindingContext().get(BindingContext.RESOLVED_CALL, expression)
        if (resolvedCall != null) {
            val callableDescriptor = resolvedCall.getResultingDescriptor()
            if (callableDescriptor is VariableDescriptor) {
                val compileTimeConstant = trace.getBindingContext().get(COMPILE_TIME_INITIALIZER, callableDescriptor)
                if (compileTimeConstant == null) return null

                val value: Any? =
                        if (compileTimeConstant is IntegerValueTypeConstant)
                            compileTimeConstant.getValue(expectedType ?: TypeUtils.NO_EXPECTED_TYPE)
                        else
                            compileTimeConstant.getValue()
                return createCompileTimeConstant(value, expression, expectedType, false,
                                                 AnnotationUtils.isPropertyCompileTimeConstant(callableDescriptor))
            }
        }
        return null
    }

    override fun visitQualifiedExpression(expression: JetQualifiedExpression, expectedType: JetType?): CompileTimeConstant<*>? {
        val selectorExpression = expression.getSelectorExpression()
        // 1.toInt(); 1.plus(1);
        if (selectorExpression is JetCallExpression) {
            val calleeExpression = selectorExpression.getCalleeExpression()
            if (calleeExpression !is JetSimpleNameExpression) {
                return null
            }

            val receiverExpression = expression.getReceiverExpression()
            return evaluateCall(expression, calleeExpression, receiverExpression, expectedType)
        }

        // MyEnum.A, Integer.MAX_VALUE
        if (selectorExpression != null) {
            val compileTimeConstant = evaluate(selectorExpression, expectedType)
            if (trace.get(BindingContext.IS_PURE_CONSTANT_EXPRESSION, selectorExpression) == true) {
                trace.record(BindingContext.IS_PURE_CONSTANT_EXPRESSION, expression, true)
            }
            return compileTimeConstant
        }

        return null
    }

    override fun visitCallExpression(expression: JetCallExpression, expectedType: JetType?): CompileTimeConstant<*>? {
        val call = trace.getBindingContext().get(BindingContext.RESOLVED_CALL, expression.getCalleeExpression())
        if (call == null) return null

        val resultingDescriptor = call.getResultingDescriptor()
        if (resultingDescriptor == null) return null

        // array()
        if (AnnotationUtils.isArrayMethodCall(call)) {
            val varargType = resultingDescriptor.getValueParameters().first?.getVarargElementType()!!

            val arguments = call.getValueArguments().values().flatMap { resolveArguments(it.getArguments(), varargType) }
            return ArrayValue(arguments, resultingDescriptor.getReturnType()!!, true)
        }

        // Ann()
        if (resultingDescriptor is ConstructorDescriptor) {
            val classDescriptor: ClassDescriptor = resultingDescriptor.getContainingDeclaration()
            if (DescriptorUtils.isAnnotationClass(classDescriptor)) {
                val descriptor = AnnotationDescriptorImpl()
                descriptor.setAnnotationType(classDescriptor.getDefaultType())
                AnnotationResolver.resolveAnnotationArgument(descriptor, call, trace)
                return AnnotationValue(descriptor)
            }
        }

        // javaClass()
        if (AnnotationUtils.isJavaClassMethodCall(call)) {
            return JavaClassValue(resultingDescriptor.getReturnType()!!, true)
        }

        return null
    }

    private fun resolveArguments(valueArguments: List<ValueArgument>, expectedType: JetType): List<CompileTimeConstant<*>> {
        val constants = arrayListOf<CompileTimeConstant<*>>()
        for (argument in valueArguments) {
            val argumentExpression = argument.getArgumentExpression()
            if (argumentExpression != null) {
                val compileTimeConstant = evaluate(argumentExpression, expectedType)
                if (compileTimeConstant != null) {
                    constants.add(compileTimeConstant)
                }
            }
        }
        return constants
    }

    override fun visitJetElement(element: JetElement, expectedType: JetType?): CompileTimeConstant<*>? {
        return null
    }

    private class OperationArgument(val value: Any, val ctcType: CompileTimeType<*>, val expression: JetExpression)

    private fun createOperationArgumentForReceiver(resolvedCall: ResolvedCall<*>, expression: JetExpression): OperationArgument? {
        val receiverExpressionType = getReceiverExpressionType(resolvedCall)
        if (receiverExpressionType == null) return null

        val receiverCompileTimeType = getCompileTimeType(receiverExpressionType)
        if (receiverCompileTimeType == null) return null

        return createOperationArgument(expression, receiverExpressionType, receiverCompileTimeType)
    }

    private fun createOperationArgumentForFirstParameter(argument: ResolvedValueArgument, parameter: ValueParameterDescriptor): OperationArgument? {
        val argumentCompileTimeType = getCompileTimeType(parameter.getType())
        if (argumentCompileTimeType == null) return null

        val arguments = argument.getArguments()
        if (arguments.size != 1) return null

        val argumentExpression = arguments.first().getArgumentExpression()
        if (argumentExpression == null) return null

        return createOperationArgument(argumentExpression, parameter.getType(), argumentCompileTimeType)
    }

    private fun createOperationArgument(expression: JetExpression, expressionType: JetType, compileTimeType: CompileTimeType<*>): OperationArgument? {
        val evaluatedConstant = evaluate(expression, expressionType)
        if (evaluatedConstant == null) return null

        if (evaluatedConstant is IntegerValueTypeConstant) {
            val evaluationResultWithNewType = evaluatedConstant.getValue(expressionType)
            return OperationArgument(evaluationResultWithNewType, compileTimeType, expression)
        }

        val evaluationResult = evaluatedConstant.getValue()
        if (evaluationResult == null) return null

        return OperationArgument(evaluationResult, compileTimeType, expression)
    }

    fun createCompileTimeConstant(value: Any?, expression: JetExpression, expectedType: JetType?, isPure: Boolean = true, canBeUsedInAnnotation: Boolean = true): CompileTimeConstant<*>? {
        val evaluatorContext = EvaluatorContext(canBeUsedInAnnotation)
        val compileTimeConstant =
                if (isPure) {
                    trace.record(BindingContext.IS_PURE_CONSTANT_EXPRESSION, expression, true)
                    createCompileTimeConstant(value, evaluatorContext, expectedType ?: TypeUtils.NO_EXPECTED_TYPE)
                }
                else createCompileTimeConstant(value, evaluatorContext)

        return compileTimeConstant
    }
}

public fun recordCompileTimeValueForInitializerIfNeeded(
        variableDescriptor: VariableDescriptor,
        initializer: JetExpression,
        variableType: JetType,
        trace: BindingTrace
) {
    if (!variableDescriptor.isVar()) {
        if (trace.get(BindingContext.COMPILE_TIME_INITIALIZER, variableDescriptor) == null) {
            val constant = ConstantExpressionEvaluator.evaluate(initializer, trace, variableType)
            if (constant != null) {
                trace.record(BindingContext.COMPILE_TIME_INITIALIZER, variableDescriptor, constant)
            }
        }
    }
}

public fun IntegerValueTypeConstant.createCompileTimeConstantWithType(expectedType: JetType): CompileTimeConstant<*>?
        = createCompileTimeConstant(getValue(expectedType), EvaluatorContext(canBeUsedInAnnotations()))

private fun hasLongSuffix(text: String) = text.endsWith('l') || text.endsWith('L')

public fun parseLong(text: String): Long? {
    try {
        fun substringLongSuffix(s: String) = if (hasLongSuffix(text)) s.substring(0, s.length - 1) else s
        fun parseLong(text: String, radix: Int) = java.lang.Long.parseLong(substringLongSuffix(text), radix)

        return when {
            text.startsWith("0x") || text.startsWith("0X") -> parseLong(text.substring(2), 16)
            text.startsWith("0b") || text.startsWith("0B") -> parseLong(text.substring(2), 2)
            else -> parseLong(text, 10)
        }
    }
    catch (e: NumberFormatException) {
        return null
    }
}

private fun parseFloatingLiteral(text: String): Any? {
    if (text.toLowerCase().endsWith('f')) {
        return parseFloat(text)
    }
    return parseDouble(text)
}

private fun parseDouble(text: String): Double? {
    try {
        return java.lang.Double.parseDouble(text)
    }
    catch (e: NumberFormatException) {
        return null
    }
}

private fun parseFloat(text: String): Float? {
    try {
        return java.lang.Float.parseFloat(text)
    }
    catch (e: NumberFormatException) {
        return null
    }
}

private fun parseBoolean(text: String): Boolean {
    if ("true".equals(text)) {
        return true
    }
    else if ("false".equals(text)) {
        return false
    }

    throw IllegalStateException("Must not happen. A boolean literal has text: " + text)
}


private fun createCompileTimeConstantForEquals(result: Any?, operationReference: JetExpression, context: EvaluatorContext): CompileTimeConstant<*>? {
    if (result is Boolean) {
        assert(operationReference is JetSimpleNameExpression, "This method should be called only for equals operations")
        val operationToken = (operationReference as JetSimpleNameExpression).getReferencedNameElementType()
        return when (operationToken) {
            JetTokens.EQEQ -> BooleanValue(result, context.canBeUsedInAnnotation)
            JetTokens.EXCLEQ -> BooleanValue(!result, context.canBeUsedInAnnotation)
            JetTokens.IDENTIFIER -> {
                assert ((operationReference as JetSimpleNameExpression).getReferencedNameAsName() == OperatorConventions.EQUALS, "This method should be called only for equals operations")
                return BooleanValue(result, context.canBeUsedInAnnotation)
            }
            else -> throw IllegalStateException("Unknown equals operation token: $operationToken ${operationReference.getText()}")
        }
    }
    return null
}

private fun createCompileTimeConstantForCompareTo(result: Any?, operationReference: JetExpression, context: EvaluatorContext): CompileTimeConstant<*>? {
    if (result is Int) {
        assert(operationReference is JetSimpleNameExpression, "This method should be called only for compareTo operations")
        val operationToken = (operationReference as JetSimpleNameExpression).getReferencedNameElementType()
        return when (operationToken) {
            JetTokens.LT -> BooleanValue(result < 0, context.canBeUsedInAnnotation)
            JetTokens.LTEQ -> BooleanValue(result <= 0, context.canBeUsedInAnnotation)
            JetTokens.GT -> BooleanValue(result > 0, context.canBeUsedInAnnotation)
            JetTokens.GTEQ -> BooleanValue(result >= 0, context.canBeUsedInAnnotation)
            JetTokens.IDENTIFIER -> {
                assert ((operationReference as JetSimpleNameExpression).getReferencedNameAsName() == OperatorConventions.COMPARE_TO, "This method should be called only for compareTo operations")
                return IntValue(result, context.canBeUsedInAnnotation)
            }
            else -> throw IllegalStateException("Unknown compareTo operation token: $operationToken")
        }
    }
    return null
}

private fun createStringConstant(value: CompileTimeConstant<*>?): StringValue? {
    return when (value) {
        is IntegerValueTypeConstant -> StringValue(value.getValue(TypeUtils.NO_EXPECTED_TYPE).toString(), value.canBeUsedInAnnotations())
        is StringValue -> value
        is IntValue, is ByteValue, is ShortValue, is LongValue,
        is CharValue,
        is DoubleValue, is FloatValue,
        is BooleanValue,
        is NullValue -> StringValue(value.getValue().toString(), value.canBeUsedInAnnotations())
        else -> null
    }
}

private fun createCompileTimeConstant(value: Any?, context: EvaluatorContext, expectedType: JetType? = null): CompileTimeConstant<*>? {
    if (expectedType == null) {
        when(value) {
            is Byte -> return ByteValue(value, context.canBeUsedInAnnotation)
            is Short -> return ShortValue(value, context.canBeUsedInAnnotation)
            is Int -> return IntValue(value, context.canBeUsedInAnnotation)
            is Long -> return LongValue(value, context.canBeUsedInAnnotation)
        }
    }
    return when(value) {
        is Byte, is Short, is Int, is Long -> getIntegerValue((value as Number).toLong(), context, expectedType)
        is Char -> CharValue(value, context.canBeUsedInAnnotation)
        is Float -> FloatValue(value, context.canBeUsedInAnnotation)
        is Double -> DoubleValue(value, context.canBeUsedInAnnotation)
        is Boolean -> BooleanValue(value, context.canBeUsedInAnnotation)
        is String -> StringValue(value, context.canBeUsedInAnnotation)
        else -> null
    }
}


fun isIntegerType(value: Any?) = value is Byte || value is Short || value is Int || value is Long

private fun getIntegerValue(value: Long, context: EvaluatorContext, expectedType: JetType): CompileTimeConstant<*>? {
    fun defaultIntegerValue(value: Long) = when (value) {
        value.toInt().toLong() -> IntValue(value.toInt(), context.canBeUsedInAnnotation)
        else -> LongValue(value, context.canBeUsedInAnnotation)
    }

    if (CompileTimeConstantChecker.noExpectedTypeOrError(expectedType)) {
        return IntegerValueTypeConstant(value, context.canBeUsedInAnnotation)
    }

    val builtIns = KotlinBuiltIns.getInstance()

    return when (TypeUtils.makeNotNullable(expectedType)) {
        builtIns.getLongType() -> LongValue(value, context.canBeUsedInAnnotation)
        builtIns.getShortType() -> when (value) {
            value.toShort().toLong() -> ShortValue(value.toShort(), context.canBeUsedInAnnotation)
            else -> defaultIntegerValue(value)
        }
        builtIns.getByteType() -> when (value) {
            value.toByte().toLong() -> ByteValue(value.toByte(), context.canBeUsedInAnnotation)
            else -> defaultIntegerValue(value)
        }
        builtIns.getCharType() -> IntValue(value.toInt(), context.canBeUsedInAnnotation)
        else -> defaultIntegerValue(value)
    }
}

private fun getReceiverExpressionType(resolvedCall: ResolvedCall<*>): JetType? {
    return when (resolvedCall.getExplicitReceiverKind()) {
        ExplicitReceiverKind.THIS_OBJECT -> resolvedCall.getThisObject().getType()
        ExplicitReceiverKind.RECEIVER_ARGUMENT -> resolvedCall.getReceiverArgument().getType()
        ExplicitReceiverKind.NO_EXPLICIT_RECEIVER -> null
        ExplicitReceiverKind.BOTH_RECEIVERS -> null
        else -> null
    }
}

private fun getCompileTimeType(c: JetType): CompileTimeType<out Any>? {
    val builtIns = KotlinBuiltIns.getInstance()
    return when (TypeUtils.makeNotNullable(c)) {
        builtIns.getIntType() -> INT
        builtIns.getByteType() -> BYTE
        builtIns.getShortType() -> SHORT
        builtIns.getLongType() -> LONG
        builtIns.getDoubleType() -> DOUBLE
        builtIns.getFloatType() -> FLOAT
        builtIns.getCharType() -> CHAR
        builtIns.getBooleanType() -> BOOLEAN
        builtIns.getStringType() -> STRING
        builtIns.getAnyType() -> ANY
        else -> null
    }
}

private class EvaluatorContext(val canBeUsedInAnnotation: Boolean)

private class CompileTimeType<T>

private val BYTE = CompileTimeType<Byte>()
private val SHORT = CompileTimeType<Short>()
private val INT = CompileTimeType<Int>()
private val LONG = CompileTimeType<Long>()
private val DOUBLE = CompileTimeType<Double>()
private val FLOAT = CompileTimeType<Float>()
private val CHAR = CompileTimeType<Char>()
private val BOOLEAN = CompileTimeType<Boolean>()
private val STRING = CompileTimeType<String>()
private val ANY = CompileTimeType<Any>()

[suppress("UNCHECKED_CAST")]
private fun <A, B> binaryOperation(
        a: CompileTimeType<A>,
        b: CompileTimeType<B>,
        functionName: String,
        operation: Function2<A, B, Any>,
        checker: Function2<BigInteger, BigInteger, BigInteger>
) = BinaryOperationKey(a, b, functionName) to Pair(operation, checker) as Pair<Function2<Any?, Any?, Any>, Function2<BigInteger, BigInteger, BigInteger>>

[suppress("UNCHECKED_CAST")]
private fun <A> unaryOperation(
        a: CompileTimeType<A>,
        functionName: String,
        operation: Function1<A, Any>,
        checker: Function1<Long, Long>
) = UnaryOperationKey(a, functionName) to Pair(operation, checker) as Pair<Function1<Any?, Any>, Function1<Long, Long>>

private data class BinaryOperationKey<A, B>(val f: CompileTimeType<out A>, val s: CompileTimeType<out B>, val functionName: String)
private data class UnaryOperationKey<A>(val f: CompileTimeType<out A>, val functionName: String)

