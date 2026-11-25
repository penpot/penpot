;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-position-data-test
  "Position data for texts edited in local commits, left unset while a page
  is still loading into WASM."
  (:require
   [app.main.data.workspace :as dw]
   [app.render-wasm.api :as wasm.api]
   [cljs.test :as t :include-macros true]))

(def ^:private text-id (random-uuid))
(def ^:private rect-id (random-uuid))
(def ^:private deleted-text-id (random-uuid))

(def ^:private objects
  {text-id         {:id text-id :type :text}
   rect-id         {:id rect-id :type :rect}
   deleted-text-id {:id deleted-text-id :type :text}})

(def ^:private commits
  [{:redo-changes [{:type :mod-obj :id text-id}
                   {:type :mod-obj :id rect-id}
                   {:type :del-obj :id deleted-text-id}]}])

(def ^:private position-data
  [{:x 0 :y 0 :width 80 :height 16 :text "hello"}])

(defn- get-with-loading
  "Runs `get-text-position-data` with the WASM loading flag set to `loading`,
  counting calls to `calculate-position-data`."
  [loading]
  (let [calls (atom 0)]
    (reset! wasm.api/shapes-loading? loading)
    (try
      (with-redefs [wasm.api/calculate-position-data
                    (fn [_shape]
                      (swap! calls inc)
                      position-data)]
        {:result (vec (dw/get-text-position-data commits objects))
         :calls  @calls})
      (finally
        (reset! wasm.api/shapes-loading? false)))))

(t/deftest computes-position-data-for-edited-texts
  (let [{:keys [result calls]} (get-with-loading false)]
    (t/is (= [[text-id position-data]] result))
    (t/is (= 1 calls))))

(t/deftest leaves-position-data-unset-while-shapes-load
  (let [{:keys [result calls]} (get-with-loading true)]
    (t/is (= [[text-id nil]] result))
    (t/is (zero? calls))))
