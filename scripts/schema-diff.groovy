// https://mvnrepository.com/artifact/com.opencsv/opencsv
@Grapes(
    @Grab(group = 'com.opencsv', module = 'opencsv', version = '5.3')
)


import com.opencsv.bean.CsvToBeanBuilder
import com.opencsv.bean.CsvBindByName

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

class SolrField {
    String name
    String type
}

class BiocacheField {

    String name
    String dataType
    Boolean indexed
    Boolean stored
    Boolean multivalue
    Boolean docvalue
    String info
    String downloadName
    String description
    String dwcTerm
    String classs
    String jsonName
    String infoUrl
    String i18nValues
    String downloadDescription
}

class DwcField {

    @CsvBindByName(column = 'term_localName', required = true)
    String name

    @CsvBindByName(column = 'term_iri', required = true)
    String termIri

    @CsvBindByName(column = 'status', required = true)
    String status

    @CsvBindByName(column = 'flags')
    String flags
}

class FieldMapping {

    BiocacheField biocacheField
    DwcField dwcField

    SolrField legacySolrField
    SolrField pipelinesSolrField
}



def toCamelCase = { it.replaceAll("(_)([A-Za-z0-9])", { Object[] o -> o[2].toUpperCase() }) }

// parse the TDWG DwC term vocabulary csv into a map of DwcFields
Map<String, DwcField> dwcFields
'https://raw.githubusercontent.com/tdwg/dwc/master/vocabulary/term_versions.csv'.toURL().withReader { Reader reader ->

    dwcFields = new CsvToBeanBuilder(reader)
            .withType(DwcField.class)
            .build()
            .parse()
            .findAll { it.status == 'recommended' }
            .collectEntries { [ (it.name): it ]}
}

JsonSlurper slurper = new JsonSlurper()

def biocacheV2Fields = slurper.parse('https://biocache-ws.ala.org.au/ws/index/fields?isMisc=true'.toURL())
def biocacheV2Schema = slurper.parse('file:./data/biocache-legacy-solr-schema.json'.toURL())
def biocacheV3Schema = slurper.parse('file:./data/biocache-pipelines-solr-schema.json'.toURL())


// create Map of all solr fields keyed by field name
Map<String, SolrField> legacySolrFields = biocacheV2Schema.schema.fields.collectEntries { [(it.name): new SolrField(name: it.name, type: it.type)] }
Map<String, SolrField> pipelinesSolrFields = biocacheV3Schema.schema.fields.collectEntries { [(it.name): new SolrField(name: it.name, type: it.type)] }

// process the biocache fields mapping to the solr schema fields
List<FieldMapping> fieldMappings = biocacheV2Fields.collect { field ->

    BiocacheField biocacheField = new BiocacheField(field)
    FieldMapping fieldMapping = new FieldMapping(biocacheField: biocacheField)

    // the the current biocache field name against the legacy solr field
    fieldMapping.legacySolrField = legacySolrFields[biocacheField.name]
    legacySolrFields.remove(biocacheField.name)

    if (pipelinesSolrFields[biocacheField.name]) {

        // check the pipelines solr field name exact match
        fieldMapping.pipelinesSolrField = pipelinesSolrFields[biocacheField.name]
        pipelinesSolrFields.remove(biocacheField.name)

    } else if (pipelinesSolrFields[biocacheField.dwcTerm]) {

        // check pipelines solr field name exact match to DWC term
        fieldMapping.pipelinesSolrField = pipelinesSolrFields[biocacheField.dwcTerm]
        pipelinesSolrFields.remove(biocacheField.dwcTerm)
    }

    if (dwcFields[biocacheField.name]) {

        // check the DWC term exact match against field name
        fieldMapping.dwcField = dwcFields[biocacheField.name]
        dwcFields.remove(biocacheField.name)

    } else if (dwcFields[biocacheField.dwcTerm]) {

        // check the DWC term exact match against field DWC term
        fieldMapping.dwcField = dwcFields[biocacheField.dwcTerm]
        dwcFields.remove(biocacheField.dwcTerm)
    }

    return fieldMapping
}

// add all unmatched legacy solr schema fields to the mapping object
fieldMappings += legacySolrFields.collect { fieldName, solrField ->

    FieldMapping fieldMapping = new FieldMapping(legacySolrField: solrField)

    if (pipelinesSolrFields[fieldName]) {

        // check the pipelines solr exact legacy solr field name
        fieldMapping.pipelinesSolrField = pipelinesSolrFields[fieldName]
        pipelinesSolrFields.remove(fieldName)

    } else if (!fieldName.startsWith('_')) {

        String camelField = toCamelCase(fieldName)

        if (pipelinesSolrFields[camelField]) {

            // check the pipelines solr camel case version of legacy solr field name
            fieldMapping.pipelinesSolrField = pipelinesSolrFields[camelField]
            pipelinesSolrFields.remove(camelField)
        }
    }

    if (dwcFields[fieldName]) {

        // check the DWC term against exact legacy solr field name
        fieldMapping.dwcField = dwcFields[fieldName]
        dwcFields.remove(fieldName)

    } else if (!fieldName.startsWith('_')) {

        String camelField = toCamelCase(fieldName)

        if (dwcFields[camelField]) {

            // check the DWC term against camel case version of legacy solr field name
            fieldMapping.dwcField = dwcFields[camelField]
            dwcFields.remove(camelField)
        }
    }

    return fieldMapping
}

// all unmatched pipelines solr fields
pipelinesSolrFields.each { fieldName, pipelinesSolrField ->

    // perform a case insensitive search for biocache field or camel case version
    FieldMapping unmatchedField = fieldMappings.find { FieldMapping fieldMapping ->

        if (fieldMapping.biocacheField) {
            boolean found = fieldMapping.biocacheField.name.equalsIgnoreCase(fieldName)
            if (!found && !fieldMapping.biocacheField.name.startsWith('_')) {
                found = toCamelCase(fieldMapping.biocacheField.name).equalsIgnoreCase(fieldName)
            }
            return found
        }
        return false
    }

    if (unmatchedField && !unmatchedField.pipelinesSolrField) {

        unmatchedField.pipelinesSolrField = pipelinesSolrFields[fieldName]

    } else {

        fieldMappings += new FieldMapping(pipelinesSolrField: pipelinesSolrField)
    }
}

// process the dynamic fields searching for unmatched legacy solr fields
biocacheV3Schema.schema.dynamicFields.each { dynamicField ->

    String regex = "^${dynamicField.name.replaceAll('\\*', '\\.\\*')}\$"

    fieldMappings.findAll { FieldMapping fieldMapping ->

        fieldMapping.legacySolrField?.name ==~ ~/${regex}/

    }.each { FieldMapping fieldMapping ->

        if (!fieldMapping.pipelinesSolrField) {

            fieldMapping.pipelinesSolrField = new SolrField(name: dynamicField.name, type: dynamicField.type)
        }
    }
}

// map the dwc terms
fieldMappings.each { FieldMapping fieldMapping ->

    String dwcFieldName = fieldMapping.biocacheField?.name

    DwcField dwcField = dwcFields[dwcFieldName]
    if (dwcField) {

        fieldMapping.dwcField = dwcField
        dwcFields.remove(dwcFieldName)

    } else {

        dwcFieldName = fieldMapping.pipelinesSolrField?.name
        dwcField = dwcFields[dwcFieldName]

        if (dwcField) {

            fieldMapping.dwcField = dwcField
            dwcFields.remove(dwcFieldName)

        } else {

            dwcFieldName = fieldMapping.legacySolrField?.name
            dwcField = dwcFields[dwcFieldName]

            if (dwcField) {

                fieldMapping.dwcField = dwcField
                dwcFields.remove(dwcFieldName)
            }
        }
    }
}

// add the unmatched dwc terms
fieldMappings += dwcFields.collect { fieldName, dwcField -> new FieldMapping(dwcField: dwcField) }

def fieldNameComparitor = { lft, rgt ->

    String lftFieldName = lft.biocacheField?.name ?: (lft.legacySolrField?.name ?: (lft.pipelinesSolrField?.name ?: lft.dwcField?.name))
    String rgtFieldName = rgt.biocacheField?.name ?: (rgt.legacySolrField?.name ?: (rgt.pipelinesSolrField?.name ?: rgt.dwcField?.name))

    if (lftFieldName == rgtFieldName) {
        return 0
    }
    if (!lftFieldName) {
        return -1
    }
    if (!rgtFieldName) {
        return 1
    }

    return lftFieldName.compareToIgnoreCase(rgtFieldName)

}

File fieldMappingFile = new File('pipeline_field_mappings.csv')

fieldMappingFile.newWriter().withWriter { Writer w ->

    w << "\"Solr V8 (pipelines)\",,\"Solr v6\",,\"Biocache Index Fields\",,\"DwC Fields\"\n"

    w <<  "\"field_name\","
    w <<  "\"field_type\","

    w <<  "\"field_name\","
    w <<  "\"field_type\","

    w <<  "\"dwc_term\","
    w <<  "\"term_iri\","
    w <<  "\"flags\","

    w <<  "\"name\","
    w <<  "\"jsonName\","
    w <<  "\"dwcTerm\","
    w <<  "\"downloadName\","
    w <<  "\"dataType\","
    w <<  "\"classs\","
    w <<  "indexed,"
    w <<  "docvalue,"
    w <<  "stored,"
    w <<  "multivalue,"
    w <<  "\"i18nValues\","
    w <<  "\"description\","
    w <<  "\"downloadDescription\","
    w <<  "\"info\","
    w <<  "\"infoUrl\""
    w <<  '\n'



    fieldMappings.sort(fieldNameComparitor).each { FieldMapping fieldMapping ->

        w << "\"${fieldMapping.pipelinesSolrField?.name ?: ''}\","
        w << "\"${fieldMapping.pipelinesSolrField?.type ?: ''}\","

        w << "\"${fieldMapping.legacySolrField?.name ?: ''}\","
        w << "\"${fieldMapping.legacySolrField?.type ?: ''}\","

        w << "\"${fieldMapping.dwcField?.name ?: ''}\","
        w << "\"${fieldMapping.dwcField?.termIri ?: ''}\","
        w << "\"${fieldMapping.dwcField?.flags ?: ''}\","

        w << "\"${fieldMapping.biocacheField?.name ?: ''}\","
        w << "\"${fieldMapping.biocacheField?.jsonName ?: ''}\","
        w << "\"${fieldMapping.biocacheField?.dwcTerm ?: ''}\","
        w << "\"${fieldMapping.biocacheField?.downloadName ?: ''}\","
        w << "\"${fieldMapping.biocacheField?.dataType ?: ''}\","
        w << "\"${fieldMapping.biocacheField?.classs ?: ''}\","
        w << "${fieldMapping.biocacheField?.indexed ?: ''},"
        w << "${fieldMapping.biocacheField?.docvalue ?: ''},"
        w << "${fieldMapping.biocacheField?.stored ?: ''},"
        w << "${fieldMapping.biocacheField?.multivalue ?: ''},"
        w << "\"${fieldMapping.biocacheField?.i18nValues ?: ''}\","
        w << "\"${fieldMapping.biocacheField?.description ?: ''}\","
        w << "\"${fieldMapping.biocacheField?.downloadDescription ?: ''}\","
        w << "\"${fieldMapping.biocacheField?.info ?: ''}\","
        w << "\"${fieldMapping.biocacheField?.infoUrl ?: ''}\""
        w << '\n'
    }
}


Map<String, String> deprecatedFields = [:]

fieldMappings.findAll { FieldMapping fieldMapping ->
    fieldMapping.legacySolrField && fieldMapping.pipelinesSolrField && !fieldMapping.pipelinesSolrField.name.contains('*') && fieldMapping.legacySolrField.name != fieldMapping.pipelinesSolrField.name
}.sort(fieldNameComparitor).each { FieldMapping fieldMapping ->
    deprecatedFields[fieldMapping.legacySolrField.name] = fieldMapping.pipelinesSolrField.name
}

new File('deprecated-fields.json').newWriter().withWriter { Writer w ->
    w << JsonOutput.prettyPrint(JsonOutput.toJson(deprecatedFields))
}