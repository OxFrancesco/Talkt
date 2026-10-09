import re
import sys
from pathlib import Path

required = set("""
clBuildProgram clCreateBuffer clCreateBufferWithProperties clCreateCommandQueue
clCreateContext clCreateImage clCreateKernel clCreateProgramWithBinary
clCreateProgramWithSource clCreateSubBuffer clEnqueueBarrierWithWaitList
clEnqueueCopyBuffer clEnqueueFillBuffer clEnqueueMarkerWithWaitList
clEnqueueNDRangeKernel clEnqueueReadBuffer clEnqueueWriteBuffer clFinish clFlush
clGetDeviceIDs clGetDeviceInfo clGetKernelInfo clGetKernelWorkGroupInfo
clGetPlatformIDs clGetPlatformInfo clGetProgramBuildInfo clGetProgramInfo
clReleaseEvent clReleaseKernel clReleaseMemObject clReleaseProgram clSetKernelArg
clWaitForEvents
""".split())
header = Path(sys.argv[1]).read_text()
pattern = r"extern\s+CL_API_ENTRY\s+(.+?)\s+CL_API_CALL\s+(cl\w+)\s*\((.*?)\)\s+CL_API_SUFFIX[^;]*;"
output = ['''#define CL_TARGET_OPENCL_VERSION 300
#define CL_USE_DEPRECATED_OPENCL_1_2_APIS
#include <CL/cl.h>
#include <android/log.h>
#include <dlfcn.h>

namespace {
void *vendor_library() {
    static void *library = [] {
        void *loaded = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
        if (!loaded) __android_log_print(ANDROID_LOG_WARN, "Talkt", "OpenCL vendor library unavailable: %s", dlerror());
        return loaded;
    }();
    return library;
}
template <typename Function> Function resolve(const char *name) {
    void *library = vendor_library();
    return library ? reinterpret_cast<Function>(dlsym(library, name)) : nullptr;
}
}
''']
for result, name, params in re.findall(pattern, header, re.S):
    if name not in required:
        continue
    required.remove(name)
    result = result.strip()
    arguments = []
    depth = 0
    start = 0
    parts = []
    for index, char in enumerate(params + ","):
        depth += char == "("
        depth -= char == ")"
        if char == "," and depth == 0:
            parts.append(params[start:index].strip())
            start = index + 1
    for part in parts:
        callback = re.search(r"CL_CALLBACK\s*\*\s*(\w+)", part)
        arguments.append(callback.group(1) if callback else re.search(r"(\w+)\s*(?:\[[^]]*\])?$", part).group(1))
    missing = "return CL_INVALID_OPERATION;" if result == "cl_int" else "return nullptr;"
    if "errcode_ret" in arguments:
        missing = "if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION; " + missing
    output.append(f'''extern "C" CL_API_ENTRY {result} CL_API_CALL {name}({params}) {{
    static auto function = resolve<decltype(&{name})>("{name}");
    if (!function) {{ {missing} }}
    return function({", ".join(arguments)});
}}
''')
if required:
    raise RuntimeError(f"Missing OpenCL declarations: {sorted(required)}")
Path(sys.argv[2]).write_text("\n".join(output))
