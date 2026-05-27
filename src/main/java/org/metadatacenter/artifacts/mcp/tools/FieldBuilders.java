package org.metadatacenter.artifacts.mcp.tools;

import org.metadatacenter.artifacts.model.core.AttributeValueField;
import org.metadatacenter.artifacts.model.core.CheckboxField;
import org.metadatacenter.artifacts.model.core.ControlledTermField;
import org.metadatacenter.artifacts.model.core.DoiField;
import org.metadatacenter.artifacts.model.core.EmailField;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifactBuilder;
import org.metadatacenter.artifacts.model.core.ImageField;
import org.metadatacenter.artifacts.model.core.LinkField;
import org.metadatacenter.artifacts.model.core.ListField;
import org.metadatacenter.artifacts.model.core.NihGrantIdField;
import org.metadatacenter.artifacts.model.core.NumericField;
import org.metadatacenter.artifacts.model.core.OrcidField;
import org.metadatacenter.artifacts.model.core.PageBreakField;
import org.metadatacenter.artifacts.model.core.PfasField;
import org.metadatacenter.artifacts.model.core.PhoneNumberField;
import org.metadatacenter.artifacts.model.core.PubMedField;
import org.metadatacenter.artifacts.model.core.RadioField;
import org.metadatacenter.artifacts.model.core.RichTextField;
import org.metadatacenter.artifacts.model.core.RorField;
import org.metadatacenter.artifacts.model.core.RridField;
import org.metadatacenter.artifacts.model.core.SectionBreakField;
import org.metadatacenter.artifacts.model.core.TemporalField;
import org.metadatacenter.artifacts.model.core.TextAreaField;
import org.metadatacenter.artifacts.model.core.TextField;
import org.metadatacenter.artifacts.model.core.YouTubeField;
import org.metadatacenter.artifacts.model.core.fields.TemporalGranularity;
import org.metadatacenter.artifacts.model.core.fields.XsdNumericDatatype;
import org.metadatacenter.artifacts.model.core.fields.XsdTemporalDatatype;

/**
 * Dispatch from kebab-case CEDAR field-type wire names to the matching per-type
 * builder. Shared between {@code create_field} and {@code add_field}: both need the
 * same name → builder mapping (with the same defaults for invariant-required
 * configuration like numeric and temporal types).
 */
final class FieldBuilders
{
  private FieldBuilders() {}

  /**
   * @param type kebab-case wire name (e.g. {@code text-field}, {@code controlled-term-field});
   *   must be a member of {@link org.metadatacenter.artifacts.model.yaml.YamlConstants#FIELD_TYPES}.
   *   Caller is expected to have validated membership before calling.
   * @return a per-type builder pre-configured with the invariant-required defaults
   *   (numeric → {@code xsd:decimal}; temporal → {@code xsd:date} + day granularity;
   *   list → {@code multipleChoice} set according to single/multi wire name).
   */
  static FieldSchemaArtifactBuilder<?> builderFor(String type)
  {
    return switch (type) {
      case "text-field" -> TextField.builder();
      case "controlled-term-field" -> ControlledTermField.builder();
      case "text-area-field" -> TextAreaField.builder();
      // Numeric and temporal fields enforce a non-null numberType / temporalType /
      // granularity invariant at build time. Pick the most general defaults for an
      // empty shell; callers can override later when value-constraint tools land.
      case "numeric-field" -> NumericField.builder().withNumericType(XsdNumericDatatype.DECIMAL);
      case "temporal-field" -> TemporalField.builder()
          .withTemporalType(XsdTemporalDatatype.DATE)
          .withTemporalGranularity(TemporalGranularity.DAY);
      case "radio-field" -> RadioField.builder();
      case "checkbox-field" -> CheckboxField.builder();
      case "single-select-list-field" -> ListField.builder().withMultipleChoice(false);
      case "multi-select-list-field" -> ListField.builder().withMultipleChoice(true);
      case "phone-number-field" -> PhoneNumberField.builder();
      case "email-field" -> EmailField.builder();
      case "link-field" -> LinkField.builder();
      case "ext-ror-field" -> RorField.builder();
      case "ext-orcid-field" -> OrcidField.builder();
      case "ext-pfas-field" -> PfasField.builder();
      case "ext-rrid-field" -> RridField.builder();
      case "ext-pubmed-field" -> PubMedField.builder();
      case "ext-nih-grant-id-field" -> NihGrantIdField.builder();
      case "ext-doi-field" -> DoiField.builder();
      case "attribute-value-field" -> AttributeValueField.builder();
      case "static-page-break" -> PageBreakField.builder();
      case "static-section-break" -> SectionBreakField.builder();
      case "static-image" -> ImageField.builder();
      case "static-rich-text" -> RichTextField.builder();
      case "static-youtube-video" -> YouTubeField.builder();
      default -> throw new IllegalArgumentException("unhandled field type: " + type);
    };
  }
}
