;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-wasm-text-test
  "An auto-grow text keeps the size its layout parent gives on a Fill axis;
  WASM measures only the axes the text owns."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.workspace.wasm-text :as dwwt]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.wasm :as thw]))

;; The WASM mock measures every text at 100x20.

(defn- text-shape
  [parent-id grow-type attrs]
  (merge {:id (uuid/next)
          :type :text
          :parent-id parent-id
          :grow-type grow-type
          :selrect {:x 16 :y 0 :width 268 :height 300}}
         attrs))

(defn- objects-with
  [layout text]
  (let [parent-id (:parent-id text)]
    {parent-id (cond-> {:id parent-id :type :frame :parent-id uuid/zero}
                 (some? layout) (assoc :layout layout))
     (:id text) text}))

(defn- new-size
  [layout grow-type attrs]
  (let [text (text-shape (uuid/next) grow-type attrs)]
    (thw/with-wasm-mocks*
      #(dwwt/get-wasm-text-new-size (objects-with layout text) text))))

(t/deftest auto-width-text-keeps-the-fill-width-of-its-layout
  (t/is (= {:width 268 :height 20}
           (new-size :flex :auto-width {:layout-item-h-sizing :fill}))))

(t/deftest auto-width-text-keeps-the-fill-height-of-its-layout
  (t/is (= {:width 100 :height 300}
           (new-size :flex :auto-width {:layout-item-v-sizing :fill}))))

(t/deftest auto-height-text-keeps-the-fill-height-of-its-layout
  (t/is (= {:width 268 :height 300}
           (new-size :grid :auto-height {:layout-item-v-sizing :fill}))))

(t/deftest auto-width-text-without-fill-takes-its-measured-size
  (t/is (= {:width 100 :height 20}
           (new-size :flex :auto-width {:layout-item-h-sizing :fix}))))

(t/deftest fill-outside-a-layout-does-not-count
  (t/is (= {:width 100 :height 20}
           (new-size nil :auto-width {:layout-item-h-sizing :fill
                                      :layout-item-v-sizing :fill}))))
