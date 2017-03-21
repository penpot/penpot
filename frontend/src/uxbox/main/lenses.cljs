(ns uxbox.main.lenses
  (:require [lentes.core :as l]))

;; --- Workspace
;; --- FIXME:  remove this ns

(def workspace (l/key :workspace))
(def workspace-flags (comp workspace (l/key :flags)))

(def selected-drawing (comp workspace (l/key :drawing)))
(def selected-shapes (comp workspace (l/key :selected)))
(def selected-page (comp workspace (l/key :page)))
(def selected-project (comp workspace (l/key :project)))
