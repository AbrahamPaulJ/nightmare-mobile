// The in-process NPU runner (nmqnn.cpp) for C++ in the same library: load a context
// binary, run it with inputs matched BY NAME, read outputs as dequantised floats.
// The JNI surface (NativeQnn) and this API share one mutex and one backend; the backend
// is initialised from Kotlin (NativeQnn.init) before either is used.
#pragma once

#include <cstddef>
#include <string>
#include <vector>

namespace nmqnn {

using Handle = void*;

/** A context binary, resident until [release]. nullptr on failure: [lastError]. */
Handle load(const std::string& path);

/** One execute. [names]/[data] are every input; [outs] come back in [outputNames] order. */
bool run(Handle h, const std::vector<std::string>& names, const std::vector<const float*>& data,
         std::vector<std::vector<float>>* outs);

std::vector<std::string> outputNames(Handle h);

/** Elements of the input named [name]; 0 when the graph has none. */
size_t inputElems(Handle h, const std::string& name);

void release(Handle h);

std::string lastError();

}  // namespace nmqnn
