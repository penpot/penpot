;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.render-wasm.fonts-test
  "font-stored? must not call into WASM while the GL context is not live
  (page-switch teardown), otherwise `_is_font_uploaded` aborts on a null
  resource pool (#13205)."
  (:require
   [app.common.render-wasm.wasm :as wasm]
   [app.common.uuid :as uuid]
   [app.render-wasm.api.fonts :as fonts]
   [cljs.test :as t :include-macros true]))

(defn- font-data
  []
  {:wasm-id (uuid/next)
   :weight 400
   :style 0})

(defn- module-with-is-font-uploaded
  [on-call]
  #js {"_is_font_uploaded" (fn [& args]
                             (on-call args)
                             1)})

(t/deftest font-stored-skips-wasm-when-not-live
  (let [called? (atom false)
        module (module-with-is-font-uploaded
                (fn [_] (reset! called? true)))]
    (with-redefs [wasm/live? (constantly false)
                  wasm/internal-module module]
      (t/is (nil? (fonts/font-stored? (font-data) false)))
      (t/is (false? @called?)))))

(t/deftest font-stored-queries-wasm-when-live
  (let [called? (atom false)
        module (module-with-is-font-uploaded
                (fn [_] (reset! called? true)))]
    (with-redefs [wasm/live? (constantly true)
                  wasm/internal-module module]
      (t/is (true? (fonts/font-stored? (font-data) false)))
      (t/is (true? @called?)))))
