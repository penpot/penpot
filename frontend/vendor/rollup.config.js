import babel from 'rollup-plugin-babel';
import resolve from 'rollup-plugin-node-resolve';
import commonjs from 'rollup-plugin-commonjs';
import globals from 'rollup-plugin-node-globals';
import builtins from 'rollup-plugin-node-builtins';
import replace from 'rollup-plugin-replace';

const plugins = [
  replace({
    'process.env.NODE_ENV': JSON.stringify('production')
  }),

  babel({
    exclude: 'node_modules/**',
    sourceMap: false
  }),

  resolve({
    mainFields: ['module', 'main'],
    // preferBuiltins: false,
    browser: true
  }),

  commonjs({
    include: 'node_modules/**',  // Default: undefined
    // if true then uses of `global` won't be dealt with by this plugin
    ignoreGlobal: false,  // Default: false
    sourceMap: false,  // Default: true
  }),

  globals(),
  builtins(),
];

export default [{
  input: "./react-color/react-color.js",
  external: ["react", "react-dom"],
  output: {
    globals: {
      "react": "React",
      "react-dom": "ReactDOM"
    },
    compact: true,
    file: './react-color/react-color.bundle.js',
    format: 'iife',
  },
  plugins: plugins
}, {
  input: "./react-dnd/react-dnd.js",
  external: ["react"],
  output: {
    globals: {
      "react": "React",
    },
    compact: true,
    file: './react-dnd/react-dnd.bundle.js',
    format: 'iife',
  },
  plugins: plugins
}, {
  input: "./datefns/datefns.js",
  output: {
    compact: true,
    file: './datefns/datefns.bundle.js',
    format: 'iife',
  },
  plugins: plugins
}];
