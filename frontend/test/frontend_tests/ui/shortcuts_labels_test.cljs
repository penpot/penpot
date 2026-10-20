;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.shortcuts-labels-test
  (:require
   [app.main.data.dashboard.shortcuts :as dsc]
   [app.main.data.viewer.shortcuts :as vsc]
   [app.main.data.workspace.grid-layout.shortcuts :as gsc]
   [app.main.data.workspace.path.shortcuts :as psc]
   [app.main.data.workspace.shortcuts :as wsc]
   [app.main.ui.shortcuts :as ss]
   [app.util.i18n :as i18n]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

;; Every shortcut command definition carries a :label fn holding a static
;; (tr "literal") (see :penpot/tr-dynamic). This locks the wiring: all
;; definition ids resolve through translation-keyname, and unknown ids
;; (stale custom shortcuts, debug commands) fall back to the raw key.
;; Exempt: :preview-frame (debug-only, no PO key) and :delete-stop
;; (colorpicker-local, never translated); both keep today's raw fallback.

(def ^:private definition-maps
  [wsc/shortcuts
   psc/shortcuts
   dsc/shortcuts
   dsc/shortcuts-sidebar-navigation
   dsc/shortcut-search
   dsc/shortcut-create-new-project
   vsc/shortcuts
   gsc/shortcuts])

(def ^:private exempt-ids
  #{:preview-frame})

(def ^:private section-ids
  [:basics :workspace :dashboard :viewer])

(def ^:private subsection-ids
  [:alignment :basics :edit :generic :main-menu :modify-layers
   :navigation-dashboard :navigation-viewer :navigation-workspace
   :panels :path-editor :shape :text-editor :tools :zoom-viewer
   :zoom-workspace])

(t/deftest command-definitions-carry-static-labels
  (with-redefs [i18n/tr (mock/stub (fn [k] (str "TR:" k)))]
    (t/testing "every definition id resolves its literal key"
      (doseq [shortcuts definition-maps
              [id entry] shortcuts
              :when (not (contains? exempt-ids id))]
        (t/is (fn? (:label entry)) (str id " has a :label fn"))
        (t/is (= (str "TR:shortcuts." (name id))
                 (ss/translation-keyname :sc id))
              (str id " resolves"))))

    (t/testing "sections resolve their literal keys"
      (doseq [id section-ids]
        (t/is (= (str "TR:shortcuts.section." (name id))
                 (ss/translation-keyname :sec id))
              (str id " resolves"))))

    (t/testing "subsections resolve, bare basics falls back to raw"
      (doseq [id (remove #{:basics} subsection-ids)]
        (t/is (= (str "TR:shortcuts.subsection." (name id))
                 (ss/translation-keyname :sub-sec id))
              (str id " resolves")))
      (t/is (= "shortcuts.subsection.basics"
               (ss/translation-keyname :sub-sec :basics))))

    (t/testing "unknown ids fall back to the raw key"
      (t/is (= "shortcuts.stale-id"
               (ss/translation-keyname :sc :stale-id)))
      (t/is (= "shortcuts.preview-frame"
               (ss/translation-keyname :sc :preview-frame))))))
