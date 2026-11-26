;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.dashboard-fonts-test
  "Uploading custom fonts from the dashboard. A family name the backend
  would reject is never sent, and its notice tells which characters are
  allowed."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.fonts :as df]
   [app.main.store :as st]
   [app.main.ui.dashboard.fonts :as fonts]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(def ^:private upload-error-message
  @#'fonts/upload-error-message)

(def ^:private handle-font-upload
  @#'fonts/handle-font-upload)

(def ^:private upload-fonts
  @#'fonts/upload-fonts)

(defn- font-item
  [font-family]
  {:id          (uuid/next)
   :font-family font-family
   :names       ["font.ttf"]})

(t/deftest upload-error-message-tells-the-allowed-characters
  (t/is (= (tr "errors.font-family-invalid-chars")
           (upload-error-message (font-item "Bad \"Name\"")))))

(t/deftest upload-error-message-is-generic-for-a-valid-name
  (t/is (= (tr "errors.bad-font" "font.ttf")
           (upload-error-message (font-item "Suisse Int'l")))))

(t/deftest ^:async handle-font-upload-sends-no-invalid-family
  (let [uploads (atom 0)
        error   (atom nil)]
    (await (mock/with-mocks*
             {df/upload-font-variant (mock/stub (fn [_] (swap! uploads inc) (rx/empty)))}
             (await (async/observe (handle-font-upload (font-item "Bad \\ Name"))
                                   :on-error #(reset! error %)))))
    (t/is (some? @error))
    (t/is (= 0 @uploads))))

(t/deftest ^:async upload-fonts-reports-a-failed-font-without-a-font
  (let [item    (font-item "Bad \"Name\"")
        emitted (atom [])
        results (atom [])]
    (await (mock/with-mocks*
             {st/emit! (mock/stub (fn [& evts] (swap! emitted into evts) nil))}
             (await (async/observe (upload-fonts [item])
                                   :on-next #(swap! results conj %)))))
    (t/is (= [[(:id item) nil]] @results))
    (t/is (= [:app.main.data.notifications/show] (map ptk/type @emitted)))))
