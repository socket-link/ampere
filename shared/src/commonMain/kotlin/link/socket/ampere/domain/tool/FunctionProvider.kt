package link.socket.ampere.domain.tool

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

typealias LLMFunction = () -> String
typealias LLMFunction1 = (JsonObject) -> String
typealias SuspendLLMFunction = suspend () -> String
typealias SuspendLLMFunction1 = suspend (JsonObject) -> String
typealias LLMCSVFunction = () -> List<List<String>>
typealias LLMCSVFunction1 = (JsonObject) -> List<List<String>>

// TODO: Simplify this
sealed class FunctionDefinition(
    open val tool: Tool,
) {
    sealed class StringReturn(
        override val tool: Tool,
    ) : FunctionDefinition(tool) {
        sealed class Standard(
            override val tool: Tool,
        ) : StringReturn(tool) {
            data class NoParams(
                override val tool: Tool,
                val function: LLMFunction,
            ) : Standard(tool)

            data class OneParam(
                override val tool: Tool,
                val function: LLMFunction1,
            ) : Standard(tool)
        }

        sealed class Suspend(
            override val tool: Tool,
        ) : StringReturn(tool) {
            data class NoParams(
                override val tool: Tool,
                val function: SuspendLLMFunction,
            ) : Standard(tool)

            data class OneParam(
                override val tool: Tool,
                val function: SuspendLLMFunction1,
            ) : Standard(tool)
        }

        suspend fun execute(jsonObject: JsonObject?): String =
            when (this) {
                is Standard.NoParams -> function.invoke()
                is Standard.OneParam -> {
                    jsonObject?.let(function::invoke)
                        ?: error("jsonObject was null")
                }
                is Suspend.NoParams -> function.invoke()
                is Suspend.OneParam -> {
                    jsonObject?.let { params ->
                        function.invoke(params)
                    } ?: error("jsonObject was null")
                }
            }
    }

    sealed class CSVReturn(
        override val tool: Tool,
    ) : FunctionDefinition(tool) {
        data class NoParams(
            override val tool: Tool,
            val function: LLMCSVFunction,
        ) : CSVReturn(tool)

        data class OneParam(
            override val tool: Tool,
            val function: LLMCSVFunction1,
        ) : CSVReturn(tool)

        operator fun invoke(jsonObject: JsonObject?): List<List<String>> =
            when (this) {
                is NoParams -> function.invoke()
                is OneParam ->
                    jsonObject?.let(function::invoke)
                        ?: error("jsonObject was null")
            }
    }
}

data class ParameterDefinition(
    val name: String,
    val isRequired: Boolean,
    val definition: JsonObject,
)

abstract class FunctionProvider(
    val name: String,
    val definition: FunctionDefinition,
) {
    companion object {
        fun provide(
            name: String,
            description: String,
            function: LLMFunction,
        ): Pair<String, FunctionProvider> =
            name to
                object : FunctionProvider(
                    name,
                    functionImpl(name, description, function),
                ) {}

        fun provide(
            name: String,
            description: String,
            function: LLMFunction1,
            parameterList: List<ParameterDefinition>,
        ): Pair<String, FunctionProvider> =
            name to
                object : FunctionProvider(
                    name,
                    functionImpl(name, description, function, parameterList),
                ) {}

        fun provideSuspend(
            name: String,
            description: String,
            function: SuspendLLMFunction,
        ): Pair<String, FunctionProvider> =
            name to
                object : FunctionProvider(
                    name,
                    suspendFunctionImpl(name, description, function),
                ) {}

        fun provideSuspend(
            name: String,
            description: String,
            function: SuspendLLMFunction1,
            parameterList: List<ParameterDefinition>,
        ): Pair<String, FunctionProvider> =
            name to
                object : FunctionProvider(
                    name,
                    suspendFunctionImpl(name, description, function, parameterList),
                ) {}

        fun provideCSV(
            name: String,
            description: String,
            function: LLMCSVFunction1,
            parameterList: List<ParameterDefinition>,
        ): Pair<String, FunctionProvider> =
            name to
                object : FunctionProvider(
                    name,
                    functionImplCsv(name, description, function, parameterList),
                ) {}

        private fun functionImpl(
            name: String,
            description: String,
            function: LLMFunction,
        ): FunctionDefinition =
            FunctionDefinition.StringReturn.Standard.NoParams(
                Tool.function(
                    name = name,
                    description = description,
                    parameters = Parameters.Empty,
                ),
                function,
            )

        private fun functionImpl(
            name: String,
            description: String,
            function: LLMFunction1,
            parameterList: List<ParameterDefinition>,
        ): FunctionDefinition =
            FunctionDefinition.StringReturn.Standard.OneParam(
                Tool.function(
                    name = name,
                    description = description,
                    parameters = Parameters.buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            parameterList.forEach { parameter ->
                                put(parameter.name, parameter.definition)
                            }
                        }
                        putJsonArray("required") {
                            parameterList.forEach { parameter ->
                                if (parameter.isRequired) {
                                    add(parameter.name)
                                }
                            }
                        }
                    },
                ),
                function,
            )

        private fun suspendFunctionImpl(
            name: String,
            description: String,
            function: SuspendLLMFunction,
        ): FunctionDefinition =
            FunctionDefinition.StringReturn.Suspend.NoParams(
                Tool.function(
                    name = name,
                    description = description,
                    parameters = Parameters.Empty,
                ),
                function,
            )

        private fun suspendFunctionImpl(
            name: String,
            description: String,
            function: SuspendLLMFunction1,
            parameterList: List<ParameterDefinition>,
        ): FunctionDefinition =
            FunctionDefinition.StringReturn.Suspend.OneParam(
                Tool.function(
                    name = name,
                    description = description,
                    parameters = Parameters.buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            parameterList.forEach { parameter ->
                                put(parameter.name, parameter.definition)
                            }
                        }
                        putJsonArray("required") {
                            parameterList.forEach { parameter ->
                                if (parameter.isRequired) {
                                    add(parameter.name)
                                }
                            }
                        }
                    },
                ),
                function,
            )

        private fun functionImplCsv(
            name: String,
            description: String,
            function: LLMCSVFunction1,
            parameterList: List<ParameterDefinition>,
        ): FunctionDefinition =
            FunctionDefinition.CSVReturn.OneParam(
                Tool.function(
                    name = name,
                    description = description,
                    parameters = Parameters.buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            parameterList.forEach { parameter ->
                                put(parameter.name, parameter.definition)
                            }
                        }
                        putJsonArray("required") {
                            parameterList.forEach { parameter ->
                                if (parameter.isRequired) {
                                    add(parameter.name)
                                }
                            }
                        }
                    },
                ),
                function,
            )
    }
}
