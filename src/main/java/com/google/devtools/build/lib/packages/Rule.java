// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.packages;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.events.ErrorEventListener;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.Attribute.ConfigurationTransition;
import com.google.devtools.build.lib.packages.License.DistributionType;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.GlobList;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.syntax.Label.SyntaxException;
import com.google.devtools.build.lib.util.BinaryPredicate;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An instance of a build rule in the build language.  A rule has a name, a
 * package to which it belongs, a class such as <code>cc_library</code>, and
 * set of typed attributes.  The set of attribute names and types is a property
 * of the rule's class.  The use of the term "class" here has nothing to do
 * with Java classes.  All rules are implemented by the same Java classes, Rule
 * and RuleClass.
 *
 * <p>Here is a typical rule as it appears in a BUILD file:
 * <pre>
 * cc_library(name = 'foo',
 *            defines = ['-Dkey=value'],
 *            srcs = ['foo.cc'],
 *            deps = ['bar'])
 * </pre>
 */
public final class Rule implements Target {
  /** Dependency predicate that includes all dependencies */
  public static final BinaryPredicate<Rule, Attribute> ALL_DEPS =
      new BinaryPredicate<Rule, Attribute>() {
        @Override
        public boolean apply(Rule x, Attribute y) {
          return true;
        }
      };

  /** Dependency predicate that excludes host dependencies */
  public static final BinaryPredicate<Rule, Attribute> NO_HOST_DEPS =
      new BinaryPredicate<Rule, Attribute>() {
    @Override
    public boolean apply(Rule rule, Attribute attribute) {
      // isHostConfiguration() is only defined for labels and label lists.
      if (attribute.getType() != Type.LABEL && attribute.getType() != Type.LABEL_LIST) {
        return true;
      }

      return attribute.getConfigurationTransition() != ConfigurationTransition.HOST;
    }
  };

  /** Dependency predicate that excludes implicit dependencies */
  public static final BinaryPredicate<Rule, Attribute> NO_IMPLICIT_DEPS =
      new BinaryPredicate<Rule, Attribute>() {
    @Override
    public boolean apply(Rule rule, Attribute attribute) {
      return rule.isAttributeValueExplicitlySpecified(attribute);
    }
  };

  /**
   * Dependency predicate that excludes those edges that are not present in the
   * configured target graph.
   */
  public static final BinaryPredicate<Rule, Attribute> NO_NODEP_ATTRIBUTES =
      new BinaryPredicate<Rule, Attribute>() {
    @Override
    public boolean apply(Rule rule, Attribute attribute) {
      return attribute.getType() != Type.NODEP_LABEL &&
          attribute.getType() != Type.NODEP_LABEL_LIST;
    }
  };

  /** Label predicate that allows every label. */
  public static final Predicate<Label> ALL_LABELS = Predicates.alwaysTrue();

  /**
   * Checks to see if the attribute has the isDirectCompileTimeInput property.
   */
  public static final BinaryPredicate<Rule, Attribute> DIRECT_COMPILE_TIME_INPUT =
      new BinaryPredicate<Rule, Attribute>() {
    @Override
    public boolean apply(Rule rule, Attribute attribute) {
      return attribute.isDirectCompileTimeInput();
    }
  };

  /**
   * Returns a predicate that computes the logical and of the two given predicates.
   */
  public static <X, Y> BinaryPredicate<X, Y> and(
      final BinaryPredicate<X, Y> a, final BinaryPredicate<X, Y> b) {
    return new BinaryPredicate<X, Y>() {
      @Override
      public boolean apply(X x, Y y) {
        return a.apply(x, y) && b.apply(x, y);
      }
    };
  }

  private final Label label;

  private final Package pkg;

  private final RuleClass ruleClass;

  private final AttributeContainer attributes;
  private final RawAttributeMapper attributeMap;

  private RuleVisibility visibility;

  private boolean containsErrors;

  private final Location location;

  private final FuncallExpression ast; // may be null

  // Initialized in the call to populateOutputFiles.
  private List<OutputFile> outputFiles;
  private ListMultimap<String, OutputFile> outputFileMap;

  Rule(Package pkg, Label label, RuleClass ruleClass, FuncallExpression ast, Location location) {
    this.pkg = Preconditions.checkNotNull(pkg);
    this.label = label;
    this.ruleClass = Preconditions.checkNotNull(ruleClass);
    this.location = Preconditions.checkNotNull(location);
    this.attributes = new AttributeContainer(ruleClass);
    this.attributeMap = new RawAttributeMapper(pkg, ruleClass, label, attributes);
    this.containsErrors = false;
    this.ast = ast;
  }

  void setVisibility(RuleVisibility visibility) {
    this.visibility = visibility;
  }

  void setAttributeValue(Attribute attribute, Object value, boolean explicit) {
    attributes.setAttributeValue(attribute, value, explicit);
  }

  void setAttributeValueByName(String attrName, Object value) {
    attributes.setAttributeValueByName(attrName, value);
  }

  void setAttributeLocation(int attrIndex, Location location) {
    attributes.setAttributeLocation(attrIndex, location);
  }

  void setAttributeLocation(Attribute attribute, Location location) {
    attributes.setAttributeLocation(attribute, location);
  }

  void setContainsErrors() {
    this.containsErrors = true;
  }

  @Override
  public Label getLabel() {
    return attributeMap.getLabel();
  }

  @Override
  public String getName() {
    return attributeMap.getName();
  }

  @Override
  public Package getPackage() {
    return pkg;
  }

  public RuleClass getRuleClassObject() {
    return ruleClass;
  }

  @Override
  public String getTargetKind() {
    return ruleClass.getTargetKind();
  }

  /**
   * Returns the class of this rule. (e.g. "cc_library")
   */
  public String getRuleClass() {
    return ruleClass.getName();
  }

  /**
   * Returns the build features that apply to this rule.
   */
  public Collection<String> getFeatures() {
    return pkg.getFeatures();
  }

  /**
   * Returns true iff the outputs of this rule should be created beneath the
   * bin directory, false if beneath genfiles.  For most rule
   * classes, this is a constant, but for genrule, it is a property of the
   * individual rule instance, derived from the 'output_to_bindir' attribute.
   */
  public boolean hasBinaryOutput() {
    return ruleClass.getName().equals("genrule") // this is unfortunate...
        ? NonconfigurableAttributeMapper.of(this).get("output_to_bindir", Type.BOOLEAN)
        : ruleClass.hasBinaryOutput();
  }

  /**
   * Returns the AST for this rule.  Returns null if the package factory chose
   * not to retain the AST when evaluateBuildFile was called for this rule's
   * package.
   */
  public FuncallExpression getSyntaxTree() {
    return ast;
  }

  /**
   * Returns true iff there were errors while constructing this rule, such as
   * attributes with missing values or values of the wrong type.
   */
  public boolean containsErrors() {
    return containsErrors;
  }

  /**
   * Returns an (unmodifiable, unordered) collection containing all the
   * Attribute definitions for this kind of rule.  (Note, this doesn't include
   * the <i>values</i> of the attributes, merely the schema.  Call
   * get[Type]Attr() methods to access the actual values.)
   */
  public Collection<Attribute> getAttributes() {
    return ruleClass.getAttributes();
  }

  /**
   * Returns true if this rule has any attributes that are configurable.
   *
   * <p>Note this is *not* the same as having attribute *types* that are configurable. For example,
   * "deps" is configurable, in that one can write a rule that sets "deps" to a configuration
   * dictionary. But if *this* rule's instance of "deps" doesn't do that, its instance
   * of "deps" is not considered configurable.
   *
   * <p>In other words, this method signals which rules might have their attribute values
   * influenced by the configuration.
   */
  public boolean hasConfigurableAttributes() {
    for (Attribute attribute : getAttributes()) {
      if (attributeMap.isConfigurable(attribute.getName(), attribute.getType())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the attribute definition whose name is {@code attrName}, or null
   * if not found.  (Use get[X]Attr for the actual value.)
   *
   * @deprecated use {@link AbstractAttributeMapper#getAttributeDefinition} instead
   */
  @Deprecated
  public Attribute getAttributeDefinition(String attrName) {
    return attributeMap.getAttributeDefinition(attrName);
  }

  /**
   * Returns an (unmodifiable, ordered) collection containing all the declared output files of this
   * rule.
   *
   * <p>All implicit output files (declared in the {@link RuleClass}) are
   * listed first, followed by any explicit files (declared via the 'outs' attribute). Additionally
   * both implicit and explicit outputs will retain the relative order in which they were declared.
   *
   * <p>This ordering is useful because it is propagated through to the list of targets returned by
   * getOuts() and allows targets to access their implicit outputs easily via
   * {@code getOuts().get(N)} (providing that N is less than the number of implicit outputs).
   *
   * <p>The fact that the relative order of the explicit outputs is also retained is less obviously
   * useful but is still well defined.
   */
  public Collection<OutputFile> getOutputFiles() {
    return outputFiles;
  }

  /**
   * Returns an (unmodifiable, ordered) map containing the list of output files for every
   * output type attribute.
   */
  public ListMultimap<String, OutputFile> getOutputFileMap() {
    return outputFileMap;
  }

  @Override
  public Location getLocation() {
    return location;
  }

  @Override
  public Rule getAssociatedRule() {
    return this;
  }

  /**
   * Returns this rule's raw attribute info, suitable for being fed into an
   * {@link AttributeMap} for user-level attribute access. Don't use this method
   * for direct attribute access.
   */
  public AttributeContainer getAttributeContainer() {
    return attributes;
  }

  /********************************************************************
   * Attribute accessor functions.
   *
   * The below provide access to attribute definitions and other generic
   * metadata.
   *
   * For access to attribute *values* (e.g. "What's the value of attribute
   * X for Rule Y?"), go through {@link RuleContext#attributes}. If no
   * RuleContext is available, create a localized {@link AbstractAttributeMapper}
   * instance instead.
   ********************************************************************/

  /**
   * Returns the default value for the attribute {@code attrName}, which may be
   * of any type, but must exist (an exception is thrown otherwise).
   */
  public Object getAttrDefaultValue(String attrName) {
    Object defaultValue = ruleClass.getAttributeByName(attrName).getDefaultValue(this);
    // Computed defaults not expected here.
    Preconditions.checkState(!(defaultValue instanceof Attribute.ComputedDefault));
    return defaultValue;
  }

  /**
   * Returns true iff the rule class has an attribute with the given name and type.
   */
  public boolean isAttrDefined(String attrName, Type<?> type) {
    return ruleClass.hasAttr(attrName, type);
  }

  /**
   * Returns true iff the value of the specified attribute is explicitly set in
   * the BUILD file (as opposed to its default value). This also returns true if
   * the value from the BUILD file is the same as the default value.
   */
  public boolean isAttributeValueExplicitlySpecified(Attribute attribute) {
    return attributes.isAttributeValueExplicitlySpecified(attribute);
  }

  public boolean isAttributeValueExplicitlySpecified(String attrName) {
    return attributeMap.isAttributeValueExplicitlySpecified(attrName);
  }

  /**
   * Returns the location of the attribute definition for this rule, if known;
   * or the location of the whole rule otherwise.  "attrName" need not be a
   * valid attribute name for this rule.
   */
  public Location getAttributeLocation(String attrName) {
    Location attrLocation = null;
    if (!attrName.equals("name")) {
      attrLocation = attributes.getAttributeLocation(attrName);
    }
    return attrLocation != null ? attrLocation : getLocation();
  }

  /**
   * Returns a new List instance containing all direct dependencies (all types).
   */
  public Collection<Label> getLabels() {
    return getLabels(Rule.ALL_DEPS);
  }

  /**
   * Returns a new Collection containing all Labels that match a given Predicate,
   * not including outputs.
   *
   * @param predicate A binary predicate that determines if a label should be
   *     included in the result. The predicate is evaluated with this rule and
   *     the attribute that contains the label. The label will be contained in the
   *     result iff (the predicate returned {@code true} and the labels are not outputs)
   */
  public Collection<Label> getLabels(final BinaryPredicate<Rule, Attribute> predicate) {
    final Set<Label> labels = new HashSet<>();
    // TODO(bazel-team): move this to AttributeMap, too. Just like visitLabels, which labels should
    // be visited may depend on the calling context. We shouldn't implicitly decide this for
    // the caller.
    AggregatingAttributeMapper.of(this).visitLabels(new AttributeMap.AcceptsLabelAttribute() {
      @Override
      public void acceptLabelAttribute(Label label, Attribute attribute) {
        if (predicate.apply(Rule.this, attribute)) {
          labels.add(label);
        }
      }
    });
    return labels;
  }

  /**
   * Check if this rule is valid according to the validityPredicate of its RuleClass.
   */
  void checkValidityPredicate(ErrorEventListener listener) {
    PredicateWithMessage<Rule> predicate = getRuleClassObject().getValidityPredicate();
    if (!predicate.apply(this)) {
      reportError(predicate.getErrorReason(this), listener);
    }
  }

  /**
   * Collects the output files (both implicit and explicit). All the implicit output files are added
   * first, followed by any explicit files. Additionally both implicit and explicit output files
   * will retain the relative order in which they were declared.
   */
  void populateOutputFiles(ErrorEventListener listener,
      Package.AbstractPackageBuilder<?, ?> pkgBuilder) {
    Preconditions.checkState(outputFiles == null);
    // Order is important here: implicit before explicit
    outputFiles = Lists.newArrayList();
    outputFileMap = LinkedListMultimap.create();
    populateImplicitOutputFiles(listener, pkgBuilder);
    populateExplicitOutputFiles(listener);
    outputFiles = ImmutableList.copyOf(outputFiles);
    outputFileMap = ImmutableListMultimap.copyOf(outputFileMap);
  }

  // Explicit output files are user-specified attributes of type OUTPUT.
  private void populateExplicitOutputFiles(ErrorEventListener listener) {
    NonconfigurableAttributeMapper nonConfigurableAttributes =
        NonconfigurableAttributeMapper.of(this);
    for (Attribute attribute : ruleClass.getAttributes()) {
      String name = attribute.getName();
      Type<?> type = attribute.getType();
      if (type == Type.OUTPUT) {
        Label outputLabel = nonConfigurableAttributes.get(name, Type.OUTPUT);
        if (outputLabel != null) {
          addLabelOutput(attribute, outputLabel, listener);
        }
      } else if (type == Type.OUTPUT_LIST) {
        for (Label label : nonConfigurableAttributes.get(name, Type.OUTPUT_LIST)) {
          addLabelOutput(attribute, label, listener);
        }
      }
    }
  }

  /**
   * Implicit output files come from rule-specific patterns, and are a function
   * of the rule's "name", "srcs", and other attributes.
   */
  private void populateImplicitOutputFiles(ErrorEventListener listener,
      Package.AbstractPackageBuilder<?, ?> pkgBuilder) {
    try {
      for (String out : ruleClass.getImplicitOutputsFunction().getImplicitOutputs(attributeMap)) {
        try {
          addOutputFile(pkgBuilder.createLabel(out), listener);
        } catch (SyntaxException e) {
          reportError("illegal output file name '" + out + "' in rule "
                      + getLabel(), listener);
        }
      }
    } catch (EvalException e) {
      reportError(e.print(), listener);
    }
  }

  private void addLabelOutput(Attribute attribute, Label label, ErrorEventListener listener) {
    if (!label.getPackageFragment().equals(pkg.getNameFragment())) {
      throw new IllegalStateException("Label for attribute " + attribute
          + " should refer to '" + pkg.getName()
          + "' but instead refers to '" + label.getPackageFragment()
          + "' (label '" + label.getName() + "')");
    }
    OutputFile outputFile = addOutputFile(label, listener);
    outputFileMap.put(attribute.getName(), outputFile);
  }

  private OutputFile addOutputFile(Label label, ErrorEventListener listener) {
    if (label.getName().equals(getName())) {
      // TODO(bazel-team): for now (23 Apr 2008) this is just a warning.  After
      // June 1st we should make it an error.
      reportWarning("target '" + getName() + "' is both a rule and a file; please choose "
                    + "another name for the rule", listener);
    }
    OutputFile outputFile = new OutputFile(pkg, label, this);
    outputFiles.add(outputFile);
    return outputFile;
  }

  void reportError(String message, ErrorEventListener listener) {
    listener.error(location, message);
    this.containsErrors = true;
  }

  void reportWarning(String message, ErrorEventListener listener) {
    listener.warn(location, message);
  }

  @Override
  public int hashCode() {
    return label.hashCode();
  }

  /**
   * Returns a string of the form "cc_binary rule //foo:foo"
   *
   * @return a string of the form "cc_binary rule //foo:foo"
   */
  @Override
  public String toString() {
    return getRuleClass() + " rule " + getLabel();
  }

 /**
   * Returns the effective visibility of this Rule. Visibility is computed from
   * these sources in this order of preference:
   *   - 'visibility' attribute
   *   - 'default_visibility;' attribute of package() declaration
   *   - public.
   */
  @Override
  public RuleVisibility getVisibility() {
    if (visibility != null) {
      return visibility;
    }

    if (getRuleClass().equals("$error_rule")) {
      return ConstantRuleVisibility.PUBLIC;
    }

    return pkg.getDefaultVisibility();
  }

  public boolean isVisibilitySpecified() {
    return visibility != null;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Set<DistributionType> getDistributions() {
    if (isAttrDefined("distribs", Type.DISTRIBUTIONS)
        && isAttributeValueExplicitlySpecified("distribs")) {
      return NonconfigurableAttributeMapper.of(this).get("distribs", Type.DISTRIBUTIONS);
    } else {
      return getPackage().getDefaultDistribs();
    }
  }

  @Override
  public License getLicense() {
    if (isAttrDefined("licenses", Type.LICENSE)
        && isAttributeValueExplicitlySpecified("licenses")) {
      return NonconfigurableAttributeMapper.of(this).get("licenses", Type.LICENSE);
    } else {
      return getPackage().getDefaultLicense();
    }
  }

  /**
   * Returns the license of the output of the binary created by this rule, or
   * null if it is not specified.
   */
  public License getToolOutputLicense() {
    if (isAttrDefined("output_licenses", Type.LICENSE)
        && isAttributeValueExplicitlySpecified("output_licenses")) {
      return NonconfigurableAttributeMapper.of(this).get("output_licenses", Type.LICENSE);
    } else {
      return null;
    }
  }

  /**
   * Returns the globs that were expanded to create an attribute value, or
   * null if unknown or not applicable.
   */
  public static GlobList<?> getGlobInfo(Object attributeValue) {
    if (attributeValue instanceof GlobList<?>) {
      return (GlobList<?>) attributeValue;
    } else {
      return null;
    }
  }

  private void checkForNullLabel(Label labelToCheck, String where) {
    if (labelToCheck == null) {
      throw new IllegalStateException(String.format(
          "null label in rule %s, %s", getLabel().toString(), where));
    }
  }

  // Consistency check: check if this label contains any weird labels (i.e.
  // null-valued, with a packageFragment that is null...). The bug that prompted
  // the introduction of this code is #2210848 (NullPointerException in
  // Package.checkForConflicts() ).
  void checkForNullLabels() {
    AggregatingAttributeMapper.of(this).visitLabels(
        new AttributeMap.AcceptsLabelAttribute() {
          @Override
          public void acceptLabelAttribute(Label labelToCheck, Attribute attribute) {
            checkForNullLabel(labelToCheck, "attribute " + attribute.getName());
          }
        });
    for (OutputFile outputFile : getOutputFiles()) {
      checkForNullLabel(outputFile.getLabel(), "output file");
    }
  }

  /**
   * Returns the Set of all tags exhibited by this target.  May be empty.
   */
  public Set<String> getRuleTags() {
    Set<String> ruleTags = new LinkedHashSet<>();
    for (Attribute attribute : getRuleClassObject().getAttributes()) {
      if (attribute.isTaggable()) {
        Type<?> attrType = attribute.getType();
        String name = attribute.getName();
        // This enforces the expectation that taggable attributes are non-configurable.
        Object value = NonconfigurableAttributeMapper.of(this).get(name, attrType);
        Set<String> tags = attrType.toTagSet(value, name);
        ruleTags.addAll(tags);
      }
    }
    return ruleTags;
  }
}
