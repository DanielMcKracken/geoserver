/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2021, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geoserver.ogcapi.stac;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.geoserver.featurestemplating.builders.TemplateBuilder;
import org.geoserver.featurestemplating.builders.impl.DynamicValueBuilder;
import org.geoserver.ogcapi.APIException;
import org.geotools.filter.AttributeExpressionImpl;
import org.opengis.feature.type.FeatureType;
import org.opengis.feature.type.PropertyDescriptor;
import org.opengis.filter.sort.SortBy;
import org.springframework.http.HttpStatus;

/**
 * Maps properties in the source template to public sortables. For a property to be sortable it
 * must:
 *
 * <ul>
 *   <li>Map to a single source property, without expressions
 *   <li>Be of a simple comparable type (strings, numbers, dates)
 * </ul>
 */
public class STACSortablesMapper {

    private final TemplateBuilder template;
    private final FeatureType itemsSchema;

    public STACSortablesMapper(TemplateBuilder template, FeatureType itemsSchema) {
        this.template = template;
        this.itemsSchema = itemsSchema;
    }

    /** Maps sortable properties to source properties */
    public Map<String, String> getSortables() {
        Map<String, String> result = new HashMap<>();
        TemplateBuilder properties =
                TemplateBuilderUtils.getBuilderFor(template, "features", "properties");
        if (properties != null) {
            TemplatePropertyVisitor visitor =
                    new TemplatePropertyVisitor(
                            properties,
                            (path, vb) -> {
                                if (isSortable(vb))
                                    result.put(path, vb.getXpath().getPropertyName());
                            });
            visitor.visit();
        }
        // force in the extra well known sortables
        result.put("collection", "parentIdentifier");
        result.put("datetime", "timeStart");

        return result;
    }

    private boolean isSortable(DynamicValueBuilder db) {
        AttributeExpressionImpl xpath = db.getXpath();
        if (xpath != null && !xpath.getPropertyName().contains("/")) {
            Object result = xpath.evaluate(itemsSchema);
            if (result instanceof PropertyDescriptor) {
                PropertyDescriptor pd = (PropertyDescriptor) result;
                Class<?> binding = pd.getType().getBinding();
                if (Number.class.isAssignableFrom(binding)
                        || Date.class.isAssignableFrom(binding)
                        || String.class.isAssignableFrom(binding)) return true;
            }
        }
        return false;
    }

    /** Maps a SortBy[] using public sortables back to source property names */
    public SortBy[] map(SortBy[] sortby) {
        Map<String, String> sortables = getSortables();
        return Arrays.stream(sortby)
                .map(sb -> mapSortable(sb, sortables))
                .toArray(n -> new SortBy[n]);
    }

    private Object mapSortable(SortBy sb, Map<String, String> sortables) {
        String sortable = sb.getPropertyName().getPropertyName();
        String sourceName = sortables.get(sortable);
        if (sourceName == null)
            throw new APIException(
                    APIException.INVALID_PARAMETER_VALUE,
                    "Unknown sortable: " + sortable,
                    HttpStatus.NOT_FOUND);
        return STACService.FF.sort(sourceName, sb.getSortOrder());
    }
}
