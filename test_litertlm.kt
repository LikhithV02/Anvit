import com.google.ai.edge.litertlm.SamplerConfig

fun test() {
    SamplerConfig(topK = 40, topP = 0.9, temperature = 1.0)
}
