;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.creation-parent-test
  (:require
   ["sax$default" :as sax]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.undo :as dwu]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.worker :as mw]
   [app.plugins.api :as api]
   [app.plugins.library :as library]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.tokens.helpers.state :as tohs]
   [potok.v2.core :as ptk]
   [tubax.core :as tubax]))

(def ^:private plugin-id "00000000-0000-0000-0000-000000000000")

(def ^:private svg
  "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"24\" height=\"24\"><rect width=\"24\" height=\"24\"/></svg>")

(t/deftest ^:async plugin-creation-uses-the-page-root
  (doseq [layout [nil :flex :grid]
          creation [:component :svg :svg-with-images]]
    (let [file  (-> (cthf/sample-file :file :page-label :page)
                    (cths/add-sample-shape :main :type :frame :x 1000 :y 1000)
                    (cths/add-sample-shape :child :parent-label :main)
                    (cthc/make-component :component :main)
                    (cths/add-sample-shape :board :type :frame :x 0 :y 0
                                           :width 400 :height 300 :layout layout
                                           :layout-grid-rows [] :layout-grid-columns []
                                           :layout-grid-cells {}))
          store (ths/setup-store file {:renderer :svg})
          uploads (atom [])
          path  [:files (:id file) :data :pages-index (thi/id :page) :objects]]
      (await
       (mock/with-mocks*
         {st/state store
          st/emit! (fn
                     ([event] (ptk/emit! store event))
                     ([event & events] (apply ptk/emit! store event events)))
          mw/ask!  (mock/stub (fn [_] (rx/empty)))
          rp/cmd! (mock/stub (fn [cmd params]
                               (swap! uploads conj [cmd params])
                               (rx/observe-on
                                :async
                                (rx/of {:id (uuid/next) :width 10 :height 20
                                        :mtype "image/png" :name "image"}))))
          ;; Node exposes sax's CommonJS parser on the default export.
          tubax/create-parser (mock/stub (fn [_] (sax/parser true #js {})))}
         (ptk/emit! store (tohs/watch-undo-stack))
         (let [^js context (api/create-context plugin-id)
               ^js created (case creation
                             :component (.instance (library/lib-component-proxy plugin-id (:id file) (thi/id :component)))
                             :svg (.createShapeFromSvg context svg)
                             :svg-with-images
                             (await (.createShapeFromSvgWithImages
                                     context
                                     "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"24\" height=\"24\"><image href=\"https://example.com/image.png\" width=\"10\" height=\"20\"/></svg>")))
               id      (obj/get created "$id")]
           (await (async/wait-for #(some? (get-in @store (conj path id))) "created shape committed"))
           (let [created-shape (get-in @store (conj path id))]
             (t/is (= uuid/zero (:parent-id created-shape)) (str creation " / " layout))
             (t/is (= uuid/zero (:frame-id created-shape)) (str creation " / " layout))
             (t/is (not (some #{id} (get-in @store (into path [(thi/id :board) :shapes])))))
             (t/is (= 1 (count (get-in @store [:workspace-undo :items])))))
           (when (= creation :svg-with-images)
             (t/is (= [:create-file-media-object-from-url] (mapv first @uploads))))
           (ptk/emit! store dwu/undo)
           (await (async/wait-for #(nil? (get-in @store (conj path id))) "creation undone"))
           (t/is (nil? (get-in @store (conj path id))))
           (t/is (empty? (get-in @store (into path [(thi/id :board) :shapes]))))
           (ptk/emit! store dwu/redo)
           (await (async/wait-for #(some? (get-in @store (conj path id))) "creation redone"))
           (set! (.-x created) 1000)
           (set! (.-y created) 1200)
           (await (async/wait-for #(= [1000 1200] ((juxt :x :y) (get-in @store (conj path id)))) "position updated"))
           (t/is (= [1000 1200] ((juxt :x :y) (get-in @store (conj path id)))))
           (let [^js page (.-currentPage context)
                 ^js board (.getShapeById page (str (thi/id :board)))]
             (.appendChild board created))
           (await (async/wait-for #(= (thi/id :board) (get-in @store (into path [id :parent-id]))) "explicit parent applied"))
           (t/is (= (thi/id :board) (get-in @store (into path [id :parent-id]))))))))))
