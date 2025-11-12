;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.functions
  "A functions helpers"
  (:require
   ["lodash/debounce.js" :as lodash-debounce]
   [app.util.rxops :refer [throttle-fn]]))

;; NOTE: this is needed because depending on the type of the build and
;; target execution evironment (browser, esm), the real export can be
;; different. All this issue is about the commonjs and esm
;; interop/conversion, because the js ecosystem decided that is should
;; work this way.
;;
;; In this concrete case, lodash exposes commonjs module which works
;; ok on browser build but for ESM build it is converted in the best
;; effort to esm module, exporting the module.exports as the default
;; property. This is why on ESM builds we need to look on .-default
;; property.
(def ^:private ext-debounce
  (or (.-default lodash-debounce)
      lodash-debounce))

(defn debounce
  ([f]
   (debounce f 0))
  ([f timeout]
   (ext-debounce f timeout #{:leading false :trailing true})))

(defn throttle
  ([f]
   (throttle-fn 0 f))
  ([f timeout]
   (throttle-fn timeout f)))
