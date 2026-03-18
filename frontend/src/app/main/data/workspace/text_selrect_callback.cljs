;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.text-selrect-callback
  "Event to update text shape selrect from WASM dimensions.
   Lives here so app.render-wasm.api can emit it without requiring shapes
   (which would create a circular dependency: shapes -> changes -> render-wasm)."
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.main.data.helpers :as dsh]
   [app.main.store :as st]
   [potok.v2.core :as ptk]))

(defn update-text-shape-selrect
  "Updates a text shape's selrect in the main store from WASM-computed dimensions.
   Used when pasting text (no text shape selected) so the selection rect matches
   the actual rendered text bounds. Emitted by app.render-wasm.api."
  [shape-id dimensions]
  (ptk/reify ::update-text-shape-selrect
    ptk/UpdateEvent
    (update [_ state]
      (let [file-id  (:current-file-id state)
            page-id  (:current-page-id state)
            objects  (dsh/lookup-page-objects state file-id page-id)
            shape    (get objects shape-id)]
        (if (and shape dimensions (:width dimensions) (:height dimensions))
          (let [center    (gsh/shape->center shape)
                transform (:transform shape (gmt/matrix))
                rect      (-> (grc/make-rect dimensions)
                              (grc/rect->points))
                points    (gsh/transform-points rect center transform)
                selrect   (gsh/calculate-selrect points (gsh/points->center points))
                path      [:files file-id :data :pages-index page-id :objects shape-id]]
            (update-in state path assoc
                       :selrect selrect
                       :points points
                       :position-data nil))
          state)))))
