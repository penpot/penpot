;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.error-boundary
  "React error boundary components"
  (:require
   ["react-error-boundary" :as reb]
   [app.common.exceptions :as ex]
   [app.config :as cf]
   [app.main.errors :as errors]
   [app.main.refs :as refs]
   [goog.functions :as gfn]
   [rumext.v2 :as mf]))

(defn reset-keys
  "The `resetKeys` prop for the boundary: the current screen name, or nil
  before the first route resolves. It never has more than one entry:
  every screen change is a new entry.

  A navigation is a fresh attempt at rendering, so a change of screen
  clears a caught error on its own. Without that, the error page stays
  on screen after the URL changes, because the boundary keeps its error
  in internal React state that only `reset-error-boundary` clears, and
  every exit from the error page (the logo, the dialogs, the feedback
  link) only navigates.

  It is a real JS array, never a ClojureScript vector:
  `react-error-boundary` compares `resetKeys` with `.length` and
  `.some`, which a `PersistentVector` does not have, so its
  `componentDidUpdate` then throws `some is not a function`.

  It holds the screen name as a *string*, not the whole route, not the
  URL and not the keyword. The library compares entries with
  `Object.is`, which compares strings by value but compares objects by
  identity, and ClojureScript does not intern keywords: two equal
  keywords are two objects, so `Object.is` on a keyword is always
  false. A keyword here would therefore read as a new key on every
  route rebuilt, and the boundary would reset on a param change inside
  the same screen — replaying the screen that just crashed. A route map
  has the same problem and would reset on every render."
  [route]
  #js [(some-> (get-in route [:data :name]) name)])

(mf/defc error-boundary*
  [{:keys [fallback children]}]
  (let [route (mf/deref refs/route)

        fallback-wrapper
        (mf/with-memo [fallback]
          (mf/fnc fallback-wrapper*
            [{:keys [error reset-error-boundary]}]
            (let [route (mf/deref refs/route)
                  data  (errors/exception->error-data error)]
              [:> fallback {:data data
                            :route route
                            :on-reset reset-error-boundary}])))

        on-error
        (mf/with-memo []
          ;; NOTE: The debounce is necessary just for simplicity,
          ;; becuase for some reasons the error is reported twice in a
          ;; very small amount of time, so we debounce for 100ms for
          ;; avoid duplicate and redundant reports
          (gfn/debounce (fn [error info]
                          ;; If the error is a stale-asset error (cross-build
                          ;; module mismatch), force a hard page reload instead
                          ;; of showing the error page to the user.
                          (cond
                            (errors/stale-asset-error? error)
                            (cf/throttled-reload :reason (ex-message error))

                            ;; If the error is known to be harmless (browser
                            ;; extensions, React DOM conflicts, etc.), ignore it
                            ;; silently — the global uncaught-error-handler
                            ;; already does this, but react-error-boundary's
                            ;; onError fires independently of the window.onerror
                            ;; pipeline, so we must also filter here.
                            (errors/is-ignorable-exception? error)
                            nil

                            :else
                            (do
                              (set! errors/last-exception error)
                              (ex/print-throwable error)
                              (js/console.error
                               "Component trace: \n"
                               (unchecked-get info "componentStack")
                               "\n"
                               error))))
                        100))]

    [:> reb/ErrorBoundary
     {:FallbackComponent fallback-wrapper
      :onError on-error
      :resetKeys (reset-keys route)}
     children]))
