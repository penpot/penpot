;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.fix-deleted-fonts-test
  (:require
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.main.data.changes :as dwc]
   [app.main.data.workspace.fix-deleted-fonts :as fdf]
   [app.main.fonts :as fonts]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before cthi/reset-idmap!})

(def ^:private missing-font-id
  "custom-missing-font-id")

(defn- text-content-with-font
  [text font-id font-family]
  (-> (cts/setup-shape {:type :text :x 0 :y 0 :grow-type :auto-width})
      :content
      (txt/change-text text :font-id font-id :font-family font-family)))

(defn- file-with-text-shape
  [font-id font-family]
  (-> (cthf/sample-file :file1 :page-label :page1)
      (cths/add-sample-shape :text
                             {:type :text
                              :content (text-content-with-font "TEST"
                                                               font-id
                                                               font-family)})))

(defn- watch-fix-deleted-fonts-events
  [file]
  (let [events (atom [])
        store  (ths/setup-store file)
        state  @store]
    (when-let [result (ptk/watch (fdf/fix-deleted-fonts-for-page (:id file)
                                                                 (cthf/current-page-id file))
                                 state
                                 (rx/empty))]
      (->> result (rx/subs! #(swap! events conj %))))
    @events))

(t/deftest fix-deleted-fonts-skips-unfixable-missing-font
  (let [file   (file-with-text-shape missing-font-id "Gibson")
        events (watch-fix-deleted-fonts-events file)]
    (t/is (empty? events)
          "missing custom fonts with no replacement must not commit changes")))

(t/deftest fix-deleted-fonts-remaps-when-replacement-exists
  (let [replacement-id "custom-gibson-replacement"
        cleanup        #(swap! fonts/fontsdb dissoc replacement-id)]
    (try
      (swap! fonts/fontsdb assoc replacement-id
             {:id replacement-id
              :family "Gibson"
              :name "Gibson"
              :variants []})
      (let [file   (file-with-text-shape missing-font-id "Gibson")
            events (watch-fix-deleted-fonts-events file)]
        (t/is (= 1 (count events)))
        (t/is (ptk/type? ::dwc/commit-changes (first events))))
      (finally
        (cleanup)))))
