;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.imposters)

;; This is needed to avoid a circular dependency between
;; app.main.ui.workspace.shapes.frame and app.util.imposters
(defonce render-fn (atom nil))

(defn init!
  [fn]
  (reset! render-fn fn))
