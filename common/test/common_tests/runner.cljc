;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.runner
  (:require
   [clojure.test :as t]
   [common-tests.buffer-test]
   [common-tests.colors-test]
   [common-tests.data-test]
   [common-tests.files-changes-test]
   [common-tests.files-migrations-test]
   [common-tests.geom-align-test]
   [common-tests.geom-bounds-map-test]
   [common-tests.geom-grid-test]
   [common-tests.geom-line-test]
   [common-tests.geom-modif-tree-test]
   [common-tests.geom-modifiers-test]
   [common-tests.geom-point-test]
   [common-tests.geom-proportions-test]
   [common-tests.geom-shapes-common-test]
   [common-tests.geom-shapes-corners-test]
   [common-tests.geom-shapes-effects-test]
   [common-tests.geom-shapes-intersect-test]
   [common-tests.geom-shapes-strokes-test]
   [common-tests.geom-shapes-test]
   [common-tests.geom-shapes-text-test]
   [common-tests.geom-shapes-tree-seq-test]
   [common-tests.geom-snap-test]
   [common-tests.geom-test]
   [common-tests.logic.chained-propagation-test]
   [common-tests.logic.comp-creation-test]
   [common-tests.logic.comp-detach-with-nested-test]
   [common-tests.logic.comp-remove-swap-slots-test]
   [common-tests.logic.comp-reset-test]
   [common-tests.logic.comp-sync-test]
   [common-tests.logic.comp-touched-test]
   [common-tests.logic.copying-and-duplicating-test]
   [common-tests.logic.duplicated-pages-test]
   [common-tests.logic.move-shapes-test]
   [common-tests.logic.multiple-nesting-levels-test]
   [common-tests.logic.swap-and-reset-test]
   [common-tests.logic.swap-as-override-test]
   [common-tests.logic.token-test]
   [common-tests.media-test]
   [common-tests.path-names-test]
   [common-tests.record-test]
   [common-tests.schema-test]
   [common-tests.svg-path-test]
   [common-tests.svg-test]
   [common-tests.text-test]
   [common-tests.time-test]
   [common-tests.types.absorb-assets-test]
   [common-tests.types.components-test]
   [common-tests.types.container-test]
   [common-tests.types.fill-test]
   [common-tests.types.modifiers-test]
   [common-tests.types.objects-map-test]
   [common-tests.types.path-data-test]
   [common-tests.types.shape-decode-encode-test]
   [common-tests.types.shape-interactions-test]
   [common-tests.types.shape-layout-test]
   [common-tests.types.token-test]
   [common-tests.types.tokens-lib-test]
   [common-tests.undo-stack-test]
   [common-tests.uuid-test]))

#?(:cljs (enable-console-print!))

#?(:cljs
   (defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
     (if (cljs.test/successful? m)
       (.exit js/process 0)
       (.exit js/process 1))))

(defn -main
  [& args]
  (t/run-tests
   'common-tests.buffer-test
   'common-tests.colors-test
   'common-tests.data-test
   'common-tests.files-changes-test
   'common-tests.files-migrations-test
   'common-tests.geom-align-test
   'common-tests.geom-bounds-map-test
   'common-tests.geom-grid-test
   'common-tests.geom-line-test
   'common-tests.geom-modif-tree-test
   'common-tests.geom-modifiers-test
   'common-tests.geom-point-test
   'common-tests.geom-proportions-test
   'common-tests.geom-shapes-common-test
   'common-tests.geom-shapes-corners-test
   'common-tests.geom-shapes-effects-test
   'common-tests.geom-shapes-intersect-test
   'common-tests.geom-shapes-strokes-test
   'common-tests.geom-shapes-test
   'common-tests.geom-shapes-text-test
   'common-tests.geom-shapes-tree-seq-test
   'common-tests.geom-snap-test
   'common-tests.geom-test
   'common-tests.logic.chained-propagation-test
   'common-tests.logic.comp-creation-test
   'common-tests.logic.comp-detach-with-nested-test
   'common-tests.logic.comp-remove-swap-slots-test
   'common-tests.logic.comp-reset-test
   'common-tests.logic.comp-sync-test
   'common-tests.logic.comp-touched-test
   'common-tests.logic.copying-and-duplicating-test
   'common-tests.logic.duplicated-pages-test
   'common-tests.logic.move-shapes-test
   'common-tests.logic.multiple-nesting-levels-test
   'common-tests.logic.swap-and-reset-test
   'common-tests.logic.swap-as-override-test
   'common-tests.logic.token-test
   'common-tests.media-test
   'common-tests.path-names-test
   'common-tests.record-test
   'common-tests.schema-test
   'common-tests.svg-path-test
   'common-tests.svg-test
   'common-tests.text-test
   'common-tests.time-test
   'common-tests.types.absorb-assets-test
   'common-tests.types.components-test
   'common-tests.types.container-test
   'common-tests.types.fill-test
   'common-tests.types.modifiers-test
   'common-tests.types.objects-map-test
   'common-tests.types.path-data-test
   'common-tests.types.shape-decode-encode-test
   'common-tests.types.shape-interactions-test
   'common-tests.types.shape-layout-test
   'common-tests.types.token-test
   'common-tests.types.tokens-lib-test
   'common-tests.undo-stack-test
   'common-tests.uuid-test))
