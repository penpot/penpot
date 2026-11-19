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
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.tokens.correction :as dwtc]
   [beicon.v2.core :as rx]
   [frontend-tests.tokens.helpers.state :as tohs]
   [potok.v2.core :as ptk]))

(defn setup-file
  []
  (-> (ctht/sample-file-with-tokens
       :lib-fn #(-> %
                    (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set) :name "set"))
                    (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :theme)
                                                           :name "theme"
                                                           :sets #{"set"}))
                    (ctob/add-token (cthi/id :set)
                                    (ctob/make-token :id (cthi/new-id! :radius)
                                                     :name "radius"
                                                     :type :border-radius
                                                     :value 50)))
       :status-fn #(ctos/set-tokens-status % #{(cthi/id :theme)} #{(cthi/id :set)}))
      ;; holds the value of a previous version of the token
      (ctho/add-frame :stale)
      (ctht/apply-token-to-shape :stale "radius" [:r1] [:r1] 10)
      (cths/update-shape :stale :token-values {:r1 10})
      ;; from before the values were kept
      (ctho/add-frame :legacy)
      (ctht/apply-token-to-shape :legacy "radius" [:r1] [:r1] 10)))

(defn watch-commits
  [store]
  (let [commits (atom [])]
    (rx/subs! #(swap! commits conj (deref %))
              (rx/filter dch/commit? (ptk/input-stream store)))
    commits))

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

(defn with-lazy-flag
  "Run `f` with the lazy token propagation flag on; it returns the restore
  function to call when the test ends."
  []
  (let [flags cf/flags]
    (set! cf/flags (conj flags :lazy-token-propagation))
    #(set! cf/flags flags)))
