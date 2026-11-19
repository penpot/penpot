;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.worker.tokens-drain
  "Finds the stale thumbnails of a page left out of date by a lazy change of
  the tokens, and sets its token values, for the drain of the main thread
  (`app.main.data.workspace.tokens.drain`), which runs it in a worker of its
  own."
  (:require
   [app.main.data.workspace.tokens.page-values :as pv]
   [app.main.fonts :as fonts]
   [app.worker.impl :as impl]))

(defmethod impl/handler :tokens/drain-page
  [{:keys [page values token-names custom-fonts]}]
  (fonts/register-custom-fonts custom-fonts)
  (pv/drain-page page values token-names @fonts/fontsdb))

(defmethod impl/handler :tokens/stale-thumbnails
  [{:keys [page values token-names]}]
  (pv/page-stale-thumbnails page values token-names))
