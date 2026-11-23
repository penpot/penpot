;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.helpers.propagation
  "Helpers of the tests of the token values of the workspace."
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.config :as cf]
   [app.main.data.workspace.tokens.correction :as dwtc]
   [frontend-tests.tokens.helpers.state :as tohs]
   [frontend-tests.tokens.helpers.tokens :as toht]))

(defn setup-file
  []
  (-> (toht/radius-file)
      ;; holds the value of a previous version of the token
      (ctho/add-frame :stale)
      (ctht/apply-token-to-shape :stale "radius" [:r1] [:r1] 10)
      (cths/update-shape :stale :token-values {:r1 10})
      ;; from before the values were kept
      (ctho/add-frame :legacy)
      (ctht/apply-token-to-shape :legacy "radius" [:r1] [:r1] 10)))

(def stop-on-ready
  (tohs/stop-on ::dwtc/tokens-ready))

(defn setup-file-with-pages
  "`setup-file` with a second page that has a board and a main component
  applying the token. The first page stays the current one."
  []
  (let [file          (setup-file)
        first-page-id (cthf/current-page-id file)]
    (-> file
        (cthf/add-sample-page :page2)
        (ctho/add-frame :other)
        (ctht/apply-token-to-shape :other "radius" [:r1] [:r1] 50)
        (cths/update-shape :other :token-values {:r1 50})
        (ctho/add-simple-component :component1 :main-root :main-child)
        (ctht/apply-token-to-shape :main-root "radius" [:r1] [:r1] 50)
        (cths/update-shape :main-root :token-values {:r1 50})
        (vary-meta assoc :current-page-id first-page-id))))

(defn lazy-flags-without-drain
  "`cf/flags` with the lazy token propagation on and its drain off."
  []
  (-> cf/flags
      (conj :lazy-token-propagation)
      (disj :lazy-token-propagation-drain)))
