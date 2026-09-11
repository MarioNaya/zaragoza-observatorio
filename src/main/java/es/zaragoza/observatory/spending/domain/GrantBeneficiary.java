package es.zaragoza.observatory.spending.domain;

/**
 * Un beneficiario de subvenciones, tal como ADR-018 §4 decide guardarlo: <b>siempre el seudónimo, la identidad
 * solo si no es una persona</b>.
 * <p>
 * El {@code id} es el identificador que ya publica la fuente. Es estable y opaco, y es lo que permite contar
 * cuántas subvenciones recibió un mismo beneficiario y por cuánto, sin nombrar a nadie. De las 20.910 fichas
 * distintas del directorio, <b>16.450 son personas físicas</b>: de esas no entra el nombre ni el NIF, ni
 * siquiera el enmascarado.
 * <p>
 * Tampoco entra <b>ningún dato de contacto</b> —domicilio, código postal, teléfono, correo, web—, y no por
 * escrúpulo abstracto: 1.346 de los correos que publica el directorio son de un proveedor gratuito y 186 tienen
 * forma {@code nombre.apellido@…}. Son el contacto personal de quien preside la asociación, no un dato de la
 * entidad. Y el producto no los necesita: {@code spending} no tiene territorio (ADR-003 §1) y ADR-011 §2 prohíbe
 * geocodificar por dirección.
 * <p>
 * La restricción {@code spending_grant_beneficiary_no_natural_identity} (V014) impone la regla desde la base de
 * datos, como {@code spending_award_party_no_natural_identity} en la contratación.
 *
 * @param id identificador en el origen; seudónimo estable
 * @param name razón social, {@code null} si es una persona física
 * @param legalNif NIF de persona jurídica, {@code null} si es una persona física
 * @param naturalPerson si lo es por cualquiera de las dos señales (clasificación o identificador enmascarado)
 * @param classification código de clasificación del origen, sin traducir
 */
public record GrantBeneficiary(String id, String name, String legalNif, boolean naturalPerson,
		String classification) {

	public GrantBeneficiary {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("id must not be blank");
		}
		id = id.strip();
		if (naturalPerson && (name != null || legalNif != null)) {
			throw new IllegalArgumentException(
					"a natural person keeps no name and no tax identifier (ADR-018 §4)");
		}
	}

}
