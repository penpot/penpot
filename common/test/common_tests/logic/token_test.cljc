(ns common-tests.logic.token-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.tokens :as clt]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.tokens :as tht]
   [app.common.types.tokens-lib :as ctob]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- setup-file [lib-fn]
  (-> (thf/sample-file :file1)
      (tht/add-tokens-lib)
      (tht/update-tokens-lib lib-fn)))

(t/deftest generate-toggle-token-set-test
  (t/testing "toggling an active set will switch to hidden theme without user sets"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                (ctob/add-theme (ctob/make-token-theme :name "theme"
                                                                       :sets #{"foo/bar"}))
                                (ctob/set-active-themes #{"/theme"})))
          changes (clt/generate-toggle-token-set (pcb/empty-changes) (tht/get-tokens-lib file) "foo/bar")

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (= #{ctob/hidden-token-theme-path} (ctob/get-active-theme-paths redo-lib)))
      (t/is (= #{} (:sets (ctob/get-hidden-theme redo-lib))))

      ;; Undo
      (t/is (nil? (ctob/get-hidden-theme undo-lib)))
      (t/is (= #{"/theme"} (ctob/get-active-theme-paths undo-lib)))))

  (t/testing "toggling an inactive set will switch to hidden theme without user sets"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                (ctob/add-theme (ctob/make-token-theme :name "theme"
                                                                       :sets #{"foo/bar"}))
                                (ctob/set-active-themes #{"/theme"})))
          changes (clt/generate-toggle-token-set (pcb/empty-changes) (tht/get-tokens-lib file) "foo/bar")

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (= #{ctob/hidden-token-theme-path} (ctob/get-active-theme-paths redo-lib)))
      (t/is (= #{} (:sets (ctob/get-hidden-theme redo-lib))))

      ;; Undo
      (t/is (nil? (ctob/get-hidden-theme undo-lib)))
      (t/is (= #{"/theme"} (ctob/get-active-theme-paths undo-lib)))))

  (t/testing "toggling an set with hidden theme already active will toggle set in hidden theme"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                (ctob/add-theme (ctob/make-hidden-token-theme))
                                (ctob/set-active-themes #{ctob/hidden-token-theme-path})))

          changes (clt/generate-toggle-token-set-group (pcb/empty-changes) (tht/get-tokens-lib file) ["foo"])

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (= (ctob/get-active-theme-paths redo-lib) (ctob/get-active-theme-paths undo-lib)))

      (t/is (= #{"foo/bar"} (:sets (ctob/get-hidden-theme redo-lib))))

      ;; Undo
      (t/is (some? (ctob/get-hidden-theme undo-lib))))))

(t/deftest generate-toggle-token-set-group-test
  (t/testing "toggling set group with no active sets inside will activate all child sets"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                (ctob/add-set (ctob/make-token-set :name "foo/bar/baz"))
                                (ctob/add-set (ctob/make-token-set :name "foo/bar/baz/baz-child"))
                                (ctob/add-theme (ctob/make-token-theme :name "theme"))
                                (ctob/set-active-themes #{"/theme"})))
          changes (clt/generate-toggle-token-set-group (pcb/empty-changes) (tht/get-tokens-lib file) ["foo" "bar"])

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (= #{ctob/hidden-token-theme-path} (ctob/get-active-theme-paths redo-lib)))
      (t/is (= #{"foo/bar/baz" "foo/bar/baz/baz-child"} (:sets (ctob/get-hidden-theme redo-lib))))

      ;; Undo
      (t/is (nil? (ctob/get-hidden-theme undo-lib)))
      (t/is (= #{"/theme"} (ctob/get-active-theme-paths undo-lib)))))

  (t/testing "toggling set group with partially active sets inside will deactivate all child sets"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                (ctob/add-set (ctob/make-token-set :name "foo/bar/baz"))
                                (ctob/add-set (ctob/make-token-set :name "foo/bar/baz/baz-child"))
                                (ctob/add-theme (ctob/make-token-theme :name "theme"
                                                                       :sets #{"foo/bar/baz"}))
                                (ctob/set-active-themes #{"/theme"})))

          changes (clt/generate-toggle-token-set-group (pcb/empty-changes) (tht/get-tokens-lib file) ["foo" "bar"])

          redo (thf/apply-changes file changes)
          redo-lib (tht/get-tokens-lib redo)
          undo (thf/apply-undo-changes redo changes)
          undo-lib (tht/get-tokens-lib undo)]
      (t/is (= #{} (:sets (ctob/get-hidden-theme redo-lib))))
      (t/is (= #{ctob/hidden-token-theme-path} (ctob/get-active-theme-paths redo-lib)))

      ;; Undo
      (t/is (nil? (ctob/get-hidden-theme undo-lib)))
      (t/is (= #{"/theme"} (ctob/get-active-theme-paths undo-lib))))))

(t/deftest generate-move-token-set-test
  (t/testing "Ignore dropping set to the same position:"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo"))
                                (ctob/add-set (ctob/make-token-set :name "bar/baz"))))
          drop (partial clt/generate-move-token-set (pcb/empty-changes) (tht/get-tokens-lib file))]
      (t/testing "on top of identical"
        (t/is (= (pcb/empty-changes)
                 (drop {:from-index 0
                        :to-index 0
                        :position :top}))))
      (t/testing "on bottom of identical"
        (t/is (= (pcb/empty-changes)
                 (drop {:from-index 0
                        :to-index 0
                        :position :bot}))))
      (t/testing "on top of next to identical"
        (t/is (= (pcb/empty-changes)
                 (drop {:from-index 0
                        :to-index 1
                        :position :top}))))))

  (t/testing "Reorder sets when dropping next to a set:"
    (t/testing "at top"
      (let [file (setup-file #(-> %
                                  (ctob/add-set (ctob/make-token-set :name "foo"))
                                  (ctob/add-set (ctob/make-token-set :name "bar"))
                                  (ctob/add-set (ctob/make-token-set :name "baz"))))
            lib (tht/get-tokens-lib file)
            changes (clt/generate-move-token-set (pcb/empty-changes) lib {:from-index 1
                                                                          :to-index 0
                                                                          :position :top})
            redo (thf/apply-changes file changes)
            redo-sets (-> (tht/get-tokens-lib redo)
                          (ctob/get-ordered-set-names))
            undo (thf/apply-undo-changes redo changes)
            undo-sets (-> (tht/get-tokens-lib undo)
                          (ctob/get-ordered-set-names))]
        (t/is (= ["bar" "foo" "baz"] (vec redo-sets)))
        (t/testing "undo"
          (t/is (= (ctob/get-ordered-set-names lib) undo-sets)))))

    (t/testing "at bottom"
      (let [file (setup-file #(-> %
                                  (ctob/add-set (ctob/make-token-set :name "foo"))
                                  (ctob/add-set (ctob/make-token-set :name "bar"))
                                  (ctob/add-set (ctob/make-token-set :name "baz"))))
            lib (tht/get-tokens-lib file)
            changes (clt/generate-move-token-set (pcb/empty-changes) lib {:from-index 0
                                                                          :to-index 2
                                                                          :position :bot})
            redo (thf/apply-changes file changes)
            redo-sets (-> (tht/get-tokens-lib redo)
                          (ctob/get-ordered-set-names))
            undo (thf/apply-undo-changes redo changes)
            undo-sets (-> (tht/get-tokens-lib undo)
                          (ctob/get-ordered-set-names))]
        (t/is (= ["bar" "baz" "foo"] (vec redo-sets)))
        (t/testing "undo"
          (t/is (= (ctob/get-ordered-set-names lib) undo-sets)))))

    (t/testing "dropping out of set group"
      (let [file (setup-file #(-> %
                                  (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                  (ctob/add-set (ctob/make-token-set :name "foo"))))
            lib (tht/get-tokens-lib file)
            changes (clt/generate-move-token-set (pcb/empty-changes) lib {:from-index 1
                                                                          :to-index 0
                                                                          :position :top})
            redo (thf/apply-changes file changes)
            redo-sets (-> (tht/get-tokens-lib redo)
                          (ctob/get-ordered-set-names))
            undo (thf/apply-undo-changes redo changes)
            undo-sets (-> (tht/get-tokens-lib undo)
                          (ctob/get-ordered-set-names))]
        (t/is (= ["bar" "foo"] (vec redo-sets)))
        (t/testing "undo"
          (t/is (= (ctob/get-ordered-set-names lib) undo-sets)))))

    (t/testing "into set group"
      (let [file (setup-file #(-> %
                                  (ctob/add-set (ctob/make-token-set :name "foo/bar"))
                                  (ctob/add-set (ctob/make-token-set :name "foo"))))
            lib (tht/get-tokens-lib file)
            changes (clt/generate-move-token-set (pcb/empty-changes) lib {:from-index 2
                                                                          :to-index 1
                                                                          :position :bot})
            redo (thf/apply-changes file changes)
            redo-sets (-> (tht/get-tokens-lib redo)
                          (ctob/get-ordered-set-names))
            undo (thf/apply-undo-changes redo changes)
            undo-sets (-> (tht/get-tokens-lib undo)
                          (ctob/get-ordered-set-names))]
        (t/is (= ["foo/bar" "foo/foo"] (vec redo-sets)))
        (t/testing "undo"
          (t/is (= (ctob/get-ordered-set-names lib) undo-sets)))))

    (t/testing "edge-cases:"
      (t/testing "prevent overriding set to identical path"
        (let [file (setup-file #(-> %
                                    (ctob/add-set (ctob/make-token-set :name "foo/foo"))
                                    (ctob/add-set (ctob/make-token-set :name "foo"))))
              lib (tht/get-tokens-lib file)]
          (t/is (thrown?
                 #?(:cljs js/Error :clj Exception)
                 (clt/generate-move-token-set (pcb/empty-changes) lib {:from-index 2
                                                                       :to-index 0
                                                                       :position :bot})
                 #"move token set error: path exists"))
          (t/is (thrown?
                 #?(:cljs js/Error :clj Exception)
                 (clt/generate-move-token-set (pcb/empty-changes) lib {:from-index 2
                                                                       :to-index 1
                                                                       :position :bot})
                 #"move token set error: path exists"))))

      (t/testing "dropping below collapsed group doesnt add as child"
        (let [file (setup-file #(-> %
                                    (ctob/add-set (ctob/make-token-set :name "foo"))
                                    (ctob/add-set (ctob/make-token-set :name "foo/bar"))))
              lib (tht/get-tokens-lib file)
              changes (clt/generate-move-token-set (pcb/empty-changes) lib {:from-index 0
                                                                            :to-index 1
                                                                            :position :bot
                                                                            :collapsed-paths #{["foo"]}})
              redo (thf/apply-changes file changes)
              redo-sets (-> (tht/get-tokens-lib redo)
                            (ctob/get-ordered-set-names))
              undo (thf/apply-undo-changes redo changes)
              undo-sets (-> (tht/get-tokens-lib undo)
                            (ctob/get-ordered-set-names))]
          (t/is (= ["foo/bar" "foo"] (vec redo-sets)))
          (t/testing "undo"
            (t/is (= (ctob/get-ordered-set-names lib) undo-sets))))))))

(t/deftest generate-move-token-group-test
  (t/testing "Ignore dropping set group to the same position"
    (let [file (setup-file #(-> %
                                (ctob/add-set (ctob/make-token-set :name "foo"))
                                (ctob/add-set (ctob/make-token-set :name "bar/baz"))))
          drop (partial clt/generate-move-token-set-group (pcb/empty-changes) (tht/get-tokens-lib file))]
      (t/testing "on top of identical"
        (t/is (= (pcb/empty-changes)
                 (drop {:from-index 1
                        :to-index 1
                        :position :top}))))
      (t/testing "on bottom of identical"
        (t/is (= (pcb/empty-changes)
                 (drop {:from-index 1
                        :to-index 1
                        :position :bot}))))
      (t/testing "on top of next to identical"
        (t/is (= (pcb/empty-changes)
                 (drop {:from-index 1
                        :to-index 1
                        :position :top}))))))

  (t/testing "Move set groups"
    (t/testing "to top"
      (let [file (setup-file #(-> %
                                  (ctob/add-set (ctob/make-token-set :name "foo/foo"))
                                  (ctob/add-set (ctob/make-token-set :name "bar/bar"))
                                  (ctob/add-set (ctob/make-token-set :name "baz/baz"))))
            lib (tht/get-tokens-lib file)
            changes (clt/generate-move-token-set-group (pcb/empty-changes) lib {:from-index 2
                                                                                :to-index 0
                                                                                :position :top})
            redo (thf/apply-changes file changes)
            redo-sets (-> (tht/get-tokens-lib redo)
                          (ctob/get-ordered-set-names))
            undo (thf/apply-undo-changes redo changes)
            undo-sets (-> (tht/get-tokens-lib undo)
                          (ctob/get-ordered-set-names))]
        (t/is (= ["bar/bar" "foo/foo" "baz/baz"] (vec redo-sets)))

        (t/testing "undo"
          (t/is (= (ctob/get-ordered-set-names lib) undo-sets)))))

    (t/testing "to bottom"
      (let [file (setup-file #(-> %
                                  (ctob/add-set (ctob/make-token-set :name "foo/foo"))
                                  (ctob/add-set (ctob/make-token-set :name "bar"))))
            lib (tht/get-tokens-lib file)
            changes (clt/generate-move-token-set-group (pcb/empty-changes) lib {:from-index 0
                                                                                :to-index 2
                                                                                :position :bot})
            redo (thf/apply-changes file changes)
            redo-sets (-> (tht/get-tokens-lib redo)
                          (ctob/get-ordered-set-names))
            undo (thf/apply-undo-changes redo changes)
            undo-sets (-> (tht/get-tokens-lib undo)
                          (ctob/get-ordered-set-names))]
        (t/is (= ["bar" "foo/foo"] (vec redo-sets)))

        (t/testing "undo"
          (t/is (= (ctob/get-ordered-set-names lib) undo-sets)))))

    (t/testing "into set group"
      (let [file (setup-file #(-> %
                                  (ctob/add-set (ctob/make-token-set :name "foo/foo"))
                                  (ctob/add-set (ctob/make-token-set :name "bar/bar"))))
            lib (tht/get-tokens-lib file)
            changes (clt/generate-move-token-set-group (pcb/empty-changes) lib {:from-index 0
                                                                                :to-index 2
                                                                                :position :bot})
            redo (thf/apply-changes file changes)
            redo-sets (-> (tht/get-tokens-lib redo)
                          (ctob/get-ordered-set-names))
            undo (thf/apply-undo-changes redo changes)
            undo-sets (-> (tht/get-tokens-lib undo)
                          (ctob/get-ordered-set-names))]
        (t/is (= ["bar/foo/foo" "bar/bar"] (vec redo-sets)))
        (t/testing "undo"
          (t/is (= (ctob/get-ordered-set-names lib) undo-sets))))

      (t/testing "edge-cases:"
        (t/testing "prevent overriding set to identical path"
          (let [file (setup-file #(-> %
                                      (ctob/add-set (ctob/make-token-set :name "foo/identical/foo"))
                                      (ctob/add-set (ctob/make-token-set :name "identical/bar"))))
                lib (tht/get-tokens-lib file)]
            (t/is (thrown?
                   #?(:cljs js/Error :clj Exception)
                   (clt/generate-move-token-set-group (pcb/empty-changes) lib {:from-index 3
                                                                               :to-index 1
                                                                               :position :top})
                   #"move token set error: path exists"))))

        (t/testing "prevent dropping parent to child"
          (let [file (setup-file #(-> %
                                      (ctob/add-set (ctob/make-token-set :name "foo/bar/baz"))))
                lib (tht/get-tokens-lib file)]
            (t/is (thrown?
                   #?(:cljs js/Error :clj Exception)
                   (clt/generate-move-token-set-group (pcb/empty-changes) lib {:from-index 0
                                                                               :to-index 1
                                                                               :position :bot})
                   #"move token set error: parent-to-child"))))))))
