/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.tasks.redmine.model;

import com.intellij.tasks.impl.gson.Mandatory;
import com.intellij.tasks.impl.gson.RestModel;
import com.intellij.util.xmlb.annotations.Attribute;
import org.jetbrains.annotations.NotNull;

/**
 * @author Marlin Cremers
 */
@RestModel
@SuppressWarnings("UnusedDeclaration")
public class RedmineUser {
  private int id;
  @Mandatory
  private String name;

  @Override
  public final boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RedmineUser)) return false;

    RedmineUser user = (RedmineUser)o;

    return id == user.id;
  }

  @Override
  public int hashCode() {
    return id;
  }

  @Attribute("id")
  public int getId() {
    return id;
  }

  /**
   * For serialization purposes only
   */
  public void setId(int id) {
    this.id = id;
  }

  @NotNull
  public String getName() {
    return name;
  }

  @Override
  public final String toString() {
    return getName();
  }
}
