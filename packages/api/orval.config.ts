import { defineConfig } from 'orval'

/**
 * 契约生成配置（设计文档 §10.1）。
 *
 * 流水线：后端 springdoc 产出 openapi.json → orval 生成
 *   - types.gen.ts      全部 DTO → TS interface
 *   - endpoints.gen.ts  API 函数
 *   - （可选）schemas.gen.ts  Zod schema，供表单校验使用
 *
 * ⚠️ `src/generated/**` 是生成产物，禁止手工修改；
 *    CI 会执行 `orval && git diff --exit-code src/generated` 校验一致性。
 */
export default defineConfig({
  admin: {
    input: {
      // ⚠️ 指向**预处理后**的 spec，而不是后端原始产物。
      //    prepare-spec.mjs 会剥掉统一响应体 R<T> 外壳，
      //    使生成函数的返回类型与 mutator 的实际返回值一致（详见该脚本注释）。
      //    直接指向 openapi.json 会导致所有接口的返回类型都多包一层 R，
      //    调用方按类型写 res.data 却在运行时拿到 undefined。
      target: './openapi.stripped.json'
    },
    output: {
      target: './src/generated/endpoints.ts',
      // ⚠️ orval 8.x 要求该字段是**目录**：它会把每个 schema 拆成一个文件放进去。
      // 早期版本允许写文件名，升级后会直接报错退出
      // （"`schemas` is a directory, but ... names a file"）。
      schemas: './src/generated/model',
      client: 'fetch',
      mode: 'split',
      clean: true,
      // 注意：orval 8.x 已移除 `prettier` 选项（写上去会因类型不匹配而报错）。
      // 产物格式交给仓库统一的格式化工具处理，而不是在生成器里配一遍 ——
      // 两处配置格式必然漂移。
      override: {
        mutator: {
          path: './src/client.ts',
          name: 'request'
        },
        fetch: {
          // ⚠️ 必须关掉 HTTP 响应信封，否则会出现**双重解包**：
          //   生成函数返回 `{ data: R<T>, status, headers }`，
          //   而我们的 mutator 已经把 `R<T>` 剥成了 `T`。
          //   调用方于是要写 `(await pageTenants()).data.data` 才能拿到业务数据 ——
          //   既难看，又让"契约"这一层失去了简化调用的意义。
          //   关掉之后返回类型就是 mutator 的返回值 `T`，一层解包，符合预期。
          includeHttpResponseReturnType: false
        }
      }
    }
  }
})
