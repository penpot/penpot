;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.graph-wasm.api
  (:require
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.graph-wasm.wasm :as wasm]
   [app.render-wasm.helpers :as h]
   [app.render-wasm.serializers :as sr]
   [app.util.modules :as mod]
   [promesa.core :as p]))

(defn hello []
  (h/call wasm/internal-module "_hello"))

(defn init []
  (h/call wasm/internal-module "_init"))

(defn use-shape
  [id]
  (let [buffer (uuid/get-u32 id)]
    (println "use-shape" id)
    (h/call wasm/internal-module "_use_shape"
            (aget buffer 0)
            (aget buffer 1)
            (aget buffer 2)
            (aget buffer 3))))

(defn set-shape-parent-id
  [id]
  (let [buffer (uuid/get-u32 id)]
    (h/call wasm/internal-module "_set_shape_parent"
            (aget buffer 0)
            (aget buffer 1)
            (aget buffer 2)
            (aget buffer 3))))

(defn set-shape-type
  [type]
  (h/call wasm/internal-module "_set_shape_type" (sr/translate-shape-type type)))

(defn set-shape-selrect
  [selrect]
  (h/call wasm/internal-module "_set_shape_selrect"
          (dm/get-prop selrect :x1)
          (dm/get-prop selrect :y1)
          (dm/get-prop selrect :x2)
          (dm/get-prop selrect :y2)))

(defn set-object
  [shape]
  (let [id           (dm/get-prop shape :id)
        type         (dm/get-prop shape :type)
        parent-id    (get shape :parent-id)
        selrect      (get shape :selrect)
        children     (get shape :shapes)]
    (use-shape id)
    (set-shape-type type)
    (set-shape-parent-id parent-id)
    (set-shape-selrect selrect)))

(defn set-objects
  [objects]
  (doseq [shape (vals objects)]
    (set-object shape)))

(defn init-wasm-module
  [module]
  (let [default-fn (unchecked-get module "default")
        href       (cf/resolve-href "js/graph-wasm.wasm")]
    (default-fn #js {:locateFile (constantly href)})))

(defonce module
  (delay
    (if (exists? js/dynamicImport)
      (let [uri (cf/resolve-href "js/graph-wasm.js")]
        (->> (mod/import uri)
             (p/mcat init-wasm-module)
             (p/fmap (fn [default]
                       (set! wasm/internal-module default)
                       true))
             (p/merr
              (fn [cause]
                (js/console.error cause)
                (p/resolved false)))))
      (p/resolved false))))