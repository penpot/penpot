;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.render-wasm.layout-data-test
  "The paddings and margins the WASM engine receives are those the layout
   uses: a `:simple` type repeats the top and right values on the bottom
   and left."
  (:require
   [app.common.render-wasm.api.upload :as upload]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.uuid :as uuid]
   [app.render-wasm.api :as wasm.api]
   [cljs.test :as t :include-macros true]))

(defn- calls-of
  "The arguments of each call to `fn-name` of the WASM module while `f` runs."
  [fn-name f]
  (let [calls  (atom [])
        module (js-obj fn-name (fn [& args] (swap! calls conj (vec args)) nil))]
    (with-redefs [wasm/internal-module module]
      (f))
    @calls))

(def ^:private padding
  {:p1 3 :p2 6 :p3 9 :p4 12})

(t/deftest flex-layout-sends-simple-paddings
  (let [[args] (calls-of "_set_flex_layout_data"
                         #(wasm.api/set-flex-layout {:layout :flex
                                                     :layout-padding-type :simple
                                                     :layout-padding padding}))]
    (t/is (= [3 6 3 6] (subvec args 8 12)))))

(t/deftest flex-layout-sends-multiple-paddings
  (let [[args] (calls-of "_set_flex_layout_data"
                         #(wasm.api/set-flex-layout {:layout :flex
                                                     :layout-padding-type :multiple
                                                     :layout-padding padding}))]
    (t/is (= [3 6 9 12] (subvec args 8 12)))))

(t/deftest grid-layout-sends-simple-paddings
  (let [[args] (calls-of "_set_grid_layout_data"
                         #(wasm.api/set-grid-layout-data {:layout :grid
                                                          :layout-padding-type :simple
                                                          :layout-padding padding}))]
    (t/is (= [3 6 3 6] (take-last 4 args)))))

(t/deftest layout-item-sends-simple-margins
  (let [margin {:m1 3 :m2 6 :m3 9 :m4 12}
        [simple]   (calls-of "_set_layout_data"
                             #(wasm.api/set-layout-data {:layout-item-margin-type :simple
                                                         :layout-item-margin margin}))
        [untyped]  (calls-of "_set_layout_data"
                             #(wasm.api/set-layout-data {:layout-item-margin margin}))
        [multiple] (calls-of "_set_layout_data"
                             #(wasm.api/set-layout-data {:layout-item-margin-type :multiple
                                                         :layout-item-margin margin}))]
    (t/is (= [3 6 3 6] (subvec simple 0 4)))
    (t/is (= [3 6 3 6] (subvec untyped 0 4)))
    (t/is (= [3 6 9 12] (subvec multiple 0 4)))))

(defn- float-runs
  "Every run of four little-endian floats in the first `end` bytes of `dview`."
  [dview end]
  (into #{}
        (map (fn [offset] (mapv #(.getFloat32 dview (+ offset (* 4 %)) true) (range 4))))
        (range 0 (- end 15))))

(t/deftest shape-payload-sends-simple-paddings-and-margins
  (let [dview (js/DataView. (js/ArrayBuffer. 4096))
        shape {:id uuid/zero :type :frame :layout :flex
               :layout-padding-type :simple :layout-padding padding
               :layout-item-margin-type :simple :layout-item-margin {:m1 1 :m2 2 :m3 7 :m4 8}}
        end   (upload/write-shape-payload! dview 0 shape [] nil nil {:include-layout? true})
        runs  (float-runs dview end)]
    (t/is (contains? runs [3 6 3 6]))
    (t/is (not (contains? runs [3 6 9 12])))
    (t/is (contains? runs [1 2 1 2]))
    (t/is (not (contains? runs [1 2 7 8])))))
