# Plaster

A task to copy all jars and git libs into a folder

```clojure
{build.extra/plaster {:git/url "https://github.com/zerg000000/build.extra.git" 
                      :sha "{{replace with commit hash}}" 
                      :deps/root "plaster"}}
```

## Getting Started

When you have git repository that have multiple services need separate deployment.
We needs to know which artifact have changes and only deploy the changed artifact(s).


```clojure
(require '[mono.alpha.api :as mono]
         '[clojure.tools.build.api :as b])

(def basis (b/create-basis {}))

(def prev-version (first (mono/last-tags "v1.*" 1)))

(when (seq (mono/deps-changes {:changes (mono/changed-files prev-version)
                               :deps (mono/deps {:basis basis})}))
  (println "Changes is detected, start deployment")
  (deploy-service))
```

Maybe the paths are not in classpath, custom deps could be added for detecting changes

```clojure
(when (seq (mono/deps-changes {:changes (mono/changed-files prev-version)
                               :deps (-> (mono/deps {:basis basis})
                                         (conj {:deps/manifest :custom 
                                                :paths ["some extra paths"]}))}))
  (println "Changes is detected, start deployment")
  (deploy-service))
```

Run tests based on changes?

```clojure
(doseq [project (mono/all-deps-edn)]
  (b/set-project-root! (string/replace project #"/deps.edn" ""))
  (when (seq (mono/deps-changes {:changes (mono/changed-files prev-version)
                                 :deps (mono/deps {:basis (b/create-basis)})}))
    (println "Changes is detected, start test" project)
    (cb/run-tests {:aliases [:test]})))
```

## Assumption

This library make assumptions in order to work properly.

* Git repository
* Using `deps.edn` to manage dependencies
* All sub-projects are under the same repository and referenced by using `:local/root`

## How it works

Under the hook, it is just compare the `git diff` and the `:paths` info from tools.deps to see if any file is changed within the classpath.

In manual steps

### Step 1

Obtain the list of files changed

```bash
# The different between v0.0.1..HEAD
git diff --name-only v0.0.1

libs/common/src/common/core.clj
libs/web/deps.edn
```

### Step 2

Obtain the classpath information from tools.deps

```clojure
(require '[clojure.tools.build.api :as b])

(def basis (b/create-basis {}))

(def file-or-paths-we-need-to-monitor 
  (concat
    (:paths basis) ;; source paths for current deps. e.g. src, resources
    [(str b/*project-root* "/deps.edn")] ;; deps.edn for current deps. Any changes of deps.edn might mean add/remove/upgrade a dependency
    (get-all-local-root-deps-paths)) ;; source paths from all dependencies.
    )
;; => ["src" "resources" "deps.edn" "libs/common/src" "libs/common/deps.edn"]
```

### Step 3

Check file changed is under any of these paths.

```clojure
(def changed?
  (some 
    (fn [file-or-paths]
      (some #(string/starts-with? % file-or-paths) files-changed)) 
    file-or-paths-we-need-to-monitor))

(if changed?
  (do-something-useful)
  ())
```

## Caveats

Here is some Caveats that have already known. Welcome to report / discuss about the Caveats of this approache.

* Shallow clone git repository will not have the history for diffing.
* Side effect from files outside of classpath, would be ignored. e.g. loading a json that is outside of classpath.
* It detects file changes by diff git commit hash. However, it use the deps.edn from the current checkout. Some weird situations might occur if you compare commit-a, commit-b, but you checkout commit-c.
* `:local/root` pointing outside of the git repository would be ignore, since it violents the fundamental assumption of monorepo.

## How to help

