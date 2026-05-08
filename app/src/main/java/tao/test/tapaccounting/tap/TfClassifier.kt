package tao.test.tapaccounting.tap

import org.tensorflow.lite.Interpreter

open class TfClassifier {
    open fun predict(input: ArrayList<Float>, size: Int): ArrayList<ArrayList<Float>> {
        return ArrayList()
    }

    protected fun predict11(interpreter: Interpreter, input: ArrayList<Float>, size: Int): ArrayList<ArrayList<Float>> {
        val inputArray = FloatArray(input.size)
        for (i in 0 until input.size) {
            inputArray[i] = input[i]
        }
        val inputMap = HashMap<Int, Array<FloatArray>>()
        inputMap[0] = Array(1) { FloatArray(size) }
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inputArray), inputMap.toMap())
        val firstOutput = inputMap[0] as Array<FloatArray>
        val output = ArrayList<ArrayList<Float>>()
        val outputInner = ArrayList<Float>()
        for (i in 0 until size) {
            outputInner.add(firstOutput[0][i])
        }
        output.add(outputInner)
        return output
    }

    protected fun predict12(mInterpreter: Interpreter, input: ArrayList<Float>, size: Int): ArrayList<ArrayList<Float>> {
        val inputArray = java.lang.reflect.Array.newInstance(
            Float::class.javaPrimitiveType, 1, input.size, 1, 1
        ) as Array<Array<Array<FloatArray>>>
        for (i in 0 until input.size) {
            inputArray[0][i][0][0] = input[i]
        }
        val outputsArray = HashMap<Int, Any>().apply {
            this[0] = java.lang.reflect.Array.newInstance(Float::class.javaPrimitiveType, 1, size)
        }
        mInterpreter.runForMultipleInputsOutputs(arrayOf<Any>(inputArray), outputsArray)
        val firstOutput = outputsArray[0] as Array<FloatArray>
        val output = ArrayList<ArrayList<Float>>()
        val outputInner = ArrayList<Float>()
        for (i in 0 until size) {
            outputInner.add(firstOutput[0][i])
        }
        output.add(outputInner)
        return output
    }
}
