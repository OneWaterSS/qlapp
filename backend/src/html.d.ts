/**
 * 让 TypeScript 认识 `import html from './x.html'`。
 *
 * wrangler.toml 里配了 `[[rules]] type = "Text"`，打包时会把 html 当字符串塞进来。
 * 但 TS 编译器不知道这回事，所以手动声明一下，否则 import 那行会标红。
 */
declare module '*.html' {
  const content: string
  export default content
}
