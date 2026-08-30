package com.alexandria.skill.lifecycle;

/**
 * A governance lifecycle transition that a Catalog_Admin or Org_Admin can apply to a skill.
 */
public enum LifecycleAction {
    DEPRECATE,
    BLOCK,
    ARCHIVE,
    RESTORE
}
